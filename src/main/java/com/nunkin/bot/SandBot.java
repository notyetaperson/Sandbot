package com.nunkin.bot;

import com.nunkin.bot.control.InputController;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.control.RotationController;
import com.nunkin.bot.task.MineTask;
import com.nunkin.bot.task.PayTask;
import com.nunkin.bot.task.RtpTask;
import com.nunkin.bot.task.SellTask;
import com.nunkin.bot.task.SurvivalController;
import com.nunkin.bot.world.SandScanner;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.Theme;
import com.nunkin.util.TextUtil;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;

/**
 * The bot's brain. Each cycle: RTP until a sand-rich area is found, mine until the cycle timer runs out,
 * then /sell and pay the balance to the configured player.
 */
public final class SandBot {
	/** Why the bot last stopped; the Python runner only restarts it after {@link #RECOVERABLE} stops. */
	public enum StopKind {
		USER, SAFETY, RECOVERABLE
	}

	private static final SandBot INSTANCE = new SandBot();

	private final RotationController rotation = new RotationController();
	private final RtpTask rtpTask = new RtpTask(this);
	private final MineTask mineTask = new MineTask(this);
	private final SellTask sellTask = new SellTask(this);
	private final PayTask payTask = new PayTask(this);
	private final SurvivalController survival = new SurvivalController(this);
	private final BotStats stats = BotStats.load();
	/** Item types the server handed back from /sell this session. */
	private final Set<Item> unsellableItems = new HashSet<>();

	private boolean running;
	private boolean paused;
	private String pauseReason = "";
	private BotPhase phase = BotPhase.IDLE;
	private BotPhase resumePhase = BotPhase.MINING;
	private String waitReason = "";
	private long ticks;
	private long cycleStart;
	private int cycleIndex;
	private boolean midCycleSell;
	private boolean manualPay;
	private boolean attacking;
	private ScanResult lastScan;
	private Boolean savedPauseOnLostFocus;

	private StopKind stopKind;
	private String stopReason = "";
	private long stopTime;

	private boolean payeeOnline;
	private boolean payeeCameOnline;
	private long lastPayAttempt;

	private boolean respawnPending;
	private long deathAt = -1;
	private long respawnedAt = -1;

	private SandBot() {
	}

	public static SandBot get() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ lifecycle

	public void start() {
		Minecraft mc = Minecraft.getInstance();
		if (running) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			Notifications.push("Can't start", "Join a server first", Theme.WARN);
			return;
		}
		BotConfig cfg = ConfigManager.get();
		ConfigManager.save();
		Reconnector.cancel();
		running = true;
		paused = false;
		pauseReason = "";
		stopKind = null;
		stopReason = "";
		cycleIndex = 0;
		survival.reset();
		stats.startSession();
		if (cfg.keepRunningUnfocused && savedPauseOnLostFocus == null) {
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
		}
		BotLog.success("Bot started");
		if (cfg.payEnabled && cfg.payTarget.isBlank()) {
			BotLog.warn("No payout player set — sand will be sold but not paid out");
		}
		if (stats.pendingPayout > 0) {
			BotLog.info("Held payout of " + TextUtil.money(stats.pendingPayout) + " to " + cfg.payTarget + " will be retried");
		}
		Notifications.push("SandBot started", "New cycle every " + TextUtil.durationWords(cfg.cycleSeconds), Theme.GOOD);
		startCycle();
	}

	public void stopByUser() {
		stop("Stopped by you", StopKind.USER);
	}

	public void stop(String reason, StopKind kind) {
		if (!running) {
			return;
		}
		running = false;
		paused = false;
		stopKind = kind;
		stopReason = reason;
		stopTime = System.currentTimeMillis();
		haltActivity();
		InputController.setActive(false);
		phase = BotPhase.IDLE;
		survival.reset();
		stats.stopSession();
		restoreFocusOption();
		BotLog.info("Bot stopped: " + reason);
		Notifications.push("SandBot stopped", reason, kind == StopKind.USER ? Theme.TEXT_DIM : Theme.WARN);
	}

	public void toggle() {
		if (running) {
			stopByUser();
		} else {
			start();
		}
	}

	public void setPaused(boolean value, String reason) {
		if (!running || paused == value) {
			return;
		}
		paused = value;
		pauseReason = reason == null ? "" : reason;
		if (paused) {
			haltActivity();
			InputController.setActive(false);
			BotLog.info("Paused" + (pauseReason.isEmpty() ? "" : ": " + pauseReason));
		} else {
			BotLog.info("Resumed");
			if (phase == BotPhase.MINING) {
				mineTask.begin();
			}
		}
	}

	private void restoreFocusOption() {
		if (savedPauseOnLostFocus != null) {
			Minecraft.getInstance().options.pauseOnLostFocus = savedPauseOnLostFocus;
			savedPauseOnLostFocus = null;
		}
	}

	public void onDisconnect() {
		if (running) {
			BotLog.warn("Disconnected from the server");
			stop("Disconnected", StopKind.RECOVERABLE);
			Reconnector.onBotDisconnected();
		}
		lastScan = null;
		payeeOnline = false;
		respawnPending = false;
	}

	// ------------------------------------------------------------------ ticking

	public void onEndTick(Minecraft mc) {
		ticks++;
		attacking = false;
		Reconnector.tick(mc, this);
		if (ticks % 200 == 0) {
			ConfigManager.saveIfDirty();
		}
		if (ticks % 20 == 0) {
			updatePayeeStatus(mc);
		}
		handleDeath(mc);
		if (!running) {
			if (InputController.isActive()) {
				InputController.setActive(false);
			}
			return;
		}
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) {
			InputController.setActive(false);
			return;
		}

		BotConfig cfg = ConfigManager.get();
		rotation.newTick();
		survival.checkDangers(player, level);
		if (!running) {
			return;
		}
		if (paused) {
			InputController.setActive(false);
			return;
		}
		InputController.setActive(true);
		InputController.clearKeys();

		boolean inGuiStep = phase == BotPhase.SELLING || phase == BotPhase.PAYING || rtpTask.isBusy() && phase == BotPhase.SEARCHING;
		if (!inGuiStep && !InventoryUtil.hasForeignContainerOpen(player) && survival.tickActions(mc, player, level)) {
			rotation.apply(player, cfg);
			return;
		}
		if (!inGuiStep) {
			checkHeldPayout(cfg);
		}

		switch (phase) {
			case SEARCHING -> tickSearching(mc, player, level, cfg);
			case MINING -> tickMining(mc, player, level, cfg);
			case SELLING -> tickSelling(mc, player);
			case PAYING -> tickPaying(mc, player, level);
			case WAITING -> tickWaiting();
			default -> {
			}
		}
		rotation.apply(player, cfg);
	}

	private void handleDeath(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		long now = Util.getMillis();
		boolean dead = player.isDeadOrDying() || mc.screen instanceof DeathScreen;
		if (dead) {
			if (running) {
				stop("You died", StopKind.RECOVERABLE);
				respawnPending = ConfigManager.get().autoRespawn;
				deathAt = now;
				respawnedAt = -1;
			}
			if (respawnPending && (respawnedAt < 0 ? now - deathAt > 3000 : now - respawnedAt > 10000)) {
				respawn(mc);
				respawnedAt = now;
			}
			return;
		}
		if (respawnPending && respawnedAt > 0 && now - respawnedAt > 8000) {
			respawnPending = false;
			BotLog.info("Respawned — restarting the bot");
			start();
		}
	}

	/** Clicks "Respawn" on the death screen. Returns false when the player isn't dead. */
	public boolean respawn(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || !(player.isDeadOrDying() || mc.screen instanceof DeathScreen)) {
			return false;
		}
		BotLog.info("Respawning");
		player.respawn();
		mc.setScreen(null);
		return true;
	}

	private void tickSearching(Minecraft mc, LocalPlayer player, ClientLevel level, BotConfig cfg) {
		RtpTask.Status status = rtpTask.tick(mc, player, level);
		if (status == RtpTask.Status.FOUND) {
			Notifications.push("Sand found", TextUtil.number(lastScan == null ? 0 : lastScan.exposedCount)
				+ " exposed sand within " + cfg.scanRadiusChunks + " chunks", Theme.GOOD);
			enterMining();
			return;
		}
		if (cycleExpired() && !rtpTask.isBusy()) {
			BotLog.info("Cycle timer ran out while searching");
			enterSelling(false);
		}
	}

	private void tickMining(Minecraft mc, LocalPlayer player, ClientLevel level, BotConfig cfg) {
		if (cycleExpired()) {
			enterSelling(false);
			return;
		}
		MineTask.Result result = mineTask.tick(mc, player, level);
		switch (result) {
			case INVENTORY_FULL -> {
				if (cfg.sellWhenFull) {
					BotLog.info("Inventory full — selling early");
					enterSelling(true);
				} else {
					enterWaiting("Inventory full");
				}
			}
			case DEPLETED -> {
				if (cfg.rtpWhenDepleted && cycleRemainingMs() > 45_000) {
					BotLog.info("Ran out of reachable sand — looking for a new spot");
					enterSearching();
				} else {
					enterWaiting("Ran out of reachable sand");
				}
			}
			case NO_TOOL -> {
				BotLog.error("No usable shovel and hand mining is disabled");
				Notifications.push("No shovel", "Give the bot a shovel or allow hand mining", Theme.BAD);
				setPaused(true, "No usable shovel");
			}
			default -> {
			}
		}
	}

	private void tickSelling(Minecraft mc, LocalPlayer player) {
		SellTask.Status status = sellTask.tick(mc, player);
		if (status == SellTask.Status.RUNNING) {
			return;
		}
		if (midCycleSell && !cycleExpired()) {
			midCycleSell = false;
			if (!sellTask.soldAnything() && InventoryUtil.isFull(player, ConfigManager.get().includeRedSand)) {
				BotLog.warn("The inventory is full of things that can't be sold — waiting for the cycle to end");
				Notifications.push("Inventory full", "Nothing left in it can be sold", Theme.WARN);
				enterWaiting("Inventory full of unsellable items");
				return;
			}
			resume(resumePhase);
			return;
		}
		midCycleSell = false;
		enterPaying(false);
	}

	private void tickPaying(Minecraft mc, LocalPlayer player, ClientLevel level) {
		PayTask.Status status = payTask.tick(mc, player, level);
		if (status == PayTask.Status.RUNNING) {
			return;
		}
		String target = ConfigManager.get().payTarget;
		switch (status) {
			case PAID -> {
				stats.setPending(0);
				Notifications.push("Payout sent", TextUtil.money(payTask.amount()) + " -> " + target, Theme.VIOLET);
			}
			case NOTHING -> {
				if (stats.pendingPayout > 0) {
					stats.setPending(0);
				}
			}
			case DEFERRED -> {
				stats.setPending(payTask.amount());
				Notifications.push("Payout on hold", target + " is offline — paying " + TextUtil.money(payTask.amount()) + " when they're back", Theme.WARN);
			}
			default -> {
			}
		}
		if (manualPay) {
			manualPay = false;
			resume(resumePhase);
			return;
		}
		finishCycle();
		startCycle();
	}

	private void tickWaiting() {
		if (cycleExpired()) {
			enterSelling(false);
		}
	}

	private void updatePayeeStatus(Minecraft mc) {
		String target = ConfigManager.get().payTarget.trim();
		boolean online = false;
		ClientPacketListener connection = mc.getConnection();
		if (connection != null && !target.isEmpty()) {
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				if (info.getProfile().name().equalsIgnoreCase(target)) {
					online = true;
					break;
				}
			}
		}
		if (online && !payeeOnline) {
			payeeCameOnline = true;
		}
		payeeOnline = online;
	}

	/** Pays a held payout as soon as the recipient shows up in the player list, or every few minutes regardless. */
	private void checkHeldPayout(BotConfig cfg) {
		if (stats.pendingPayout <= 0 || !cfg.payEnabled || cfg.payTarget.isBlank() || !canInterrupt()) {
			payeeCameOnline = false;
			return;
		}
		long now = Util.getMillis();
		if (now - lastPayAttempt < 15_000) {
			return;
		}
		boolean appeared = payeeCameOnline;
		payeeCameOnline = false;
		if (appeared || now - lastPayAttempt >= cfg.payRetryMinutes * 60_000L) {
			BotLog.info(appeared ? cfg.payTarget + " is online — sending the held payout" : "Retrying the held payout to " + cfg.payTarget);
			forcePay();
		}
	}

	// ------------------------------------------------------------------ transitions

	private void startCycle() {
		BotConfig cfg = ConfigManager.get();
		cycleIndex++;
		cycleStart = Util.getMillis();
		stats.beginCycle(cycleIndex);
		mineTask.resetCycleCounter();
		BotLog.info("Cycle #" + cycleIndex + " started (" + TextUtil.durationWords(cfg.cycleSeconds) + ")");
		if (cfg.rtpEveryCycle || !areaStillGood()) {
			enterSearching();
		} else {
			BotLog.info("Current area still has enough sand, staying");
			enterMining();
		}
	}

	private boolean areaStillGood() {
		ScanResult scan = rescan();
		return scan != null && scan.exposedCount > ConfigManager.get().sandThreshold;
	}

	private void finishCycle() {
		BotStats.CycleRecord record = stats.current();
		if (record != null) {
			BotLog.success("Cycle #" + record.index + " done: mined " + record.mined + ", sold " + record.sold
				+ (record.paid > 0 ? ", paid " + TextUtil.money(record.paid) : ""));
		}
		stats.endCycle();
		stats.save();
	}

	private void enterSearching() {
		haltActivity();
		phase = BotPhase.SEARCHING;
		rtpTask.begin();
	}

	private void enterMining() {
		haltActivity();
		phase = BotPhase.MINING;
		mineTask.begin();
	}

	private void enterSelling(boolean midCycle) {
		haltActivity();
		if (midCycle) {
			resumePhase = phase == BotPhase.SEARCHING ? BotPhase.SEARCHING : BotPhase.MINING;
		}
		midCycleSell = midCycle;
		phase = BotPhase.SELLING;
		sellTask.begin();
	}

	private void enterPaying(boolean manual) {
		haltActivity();
		manualPay = manual;
		lastPayAttempt = Util.getMillis();
		phase = BotPhase.PAYING;
		payTask.begin();
	}

	private void enterWaiting(String reason) {
		haltActivity();
		waitReason = reason;
		phase = BotPhase.WAITING;
	}

	private void resume(BotPhase target) {
		if (target == BotPhase.SEARCHING) {
			enterSearching();
		} else {
			enterMining();
		}
	}

	private void haltActivity() {
		mineTask.halt();
		InputController.clearKeys();
		attacking = false;
	}

	// ------------------------------------------------------------------ manual controls

	private boolean canInterrupt() {
		return running && phase != BotPhase.SELLING && phase != BotPhase.PAYING && !(phase == BotPhase.SEARCHING && rtpTask.isBusy());
	}

	public boolean forceSell() {
		if (!canInterrupt()) return false;
		BotLog.info("Manual sell requested");
		enterSelling(true);
		return true;
	}

	public boolean forceRtp() {
		if (!canInterrupt()) return false;
		BotLog.info("Manual RTP requested");
		enterSearching();
		return true;
	}

	public boolean forcePay() {
		if (!canInterrupt()) return false;
		BotLog.info("Payout requested");
		resumePhase = phase == BotPhase.SEARCHING ? BotPhase.SEARCHING : BotPhase.MINING;
		enterPaying(true);
		return true;
	}

	public boolean endCycleNow() {
		if (!running) return false;
		BotLog.info("Ending the cycle early");
		cycleStart = Util.getMillis() - cycleDurationMs();
		return true;
	}

	public void handleDanger(BotConfig.DangerAction action, String reason) {
		switch (action) {
			case IGNORE -> {
			}
			case WARN -> {
				BotLog.warn(reason);
				Notifications.push("Heads up", reason, Theme.WARN);
			}
			case PAUSE -> {
				BotLog.warn(reason + " — pausing");
				Notifications.push("Paused", reason, Theme.WARN);
				setPaused(true, reason);
			}
			case RTP -> {
				BotLog.warn(reason + " — teleporting away");
				Notifications.push("Moving on", reason, Theme.WARN);
				if (canInterrupt() && phase != BotPhase.SEARCHING) {
					enterSearching();
				}
			}
			case STOP -> stop(reason, StopKind.SAFETY);
			case DISCONNECT -> {
				stop(reason, StopKind.SAFETY);
				Reconnector.suppressNext();
				Minecraft.getInstance().disconnectFromWorld(Component.literal("[SandBot] " + reason));
			}
		}
	}

	// ------------------------------------------------------------------ helpers used by tasks

	public void sendCommand(String command) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && !command.isBlank()) {
			player.connection.sendCommand(command);
		}
	}

	public void closeForeignContainer(LocalPlayer player) {
		if (InventoryUtil.hasForeignContainerOpen(player)) {
			player.closeContainer();
		}
	}

	public ScanResult rescan() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return lastScan;
		}
		BotConfig cfg = ConfigManager.get();
		lastScan = SandScanner.scan(mc.level, mc.player.chunkPosition(), cfg.scanRadiusChunks, cfg.includeRedSand);
		return lastScan;
	}

	public void onSandMined() {
		stats.addMined(1);
	}

	public void interruptForSurvival() {
		mineTask.halt();
	}

	void onChatLine(ChatWatcher.Line line) {
		if (!running || line.overlay() || line.fromPlayer() || line.raw().length() > 220) {
			return;
		}
		if (phase == BotPhase.SELLING || phase == BotPhase.PAYING || (phase == BotPhase.SEARCHING && rtpTask.isBusy())) {
			BotLog.server(line.raw());
		}
	}

	// ------------------------------------------------------------------ state for UI / mixins / runner

	public boolean isRunning() {
		return running;
	}

	public boolean isPaused() {
		return paused;
	}

	public String pauseReason() {
		return pauseReason;
	}

	public BotPhase phase() {
		return phase;
	}

	public long ticks() {
		return ticks;
	}

	public int cycleIndex() {
		return cycleIndex;
	}

	public BotStats stats() {
		return stats;
	}

	public RotationController rotation() {
		return rotation;
	}

	public ScanResult lastScan() {
		return lastScan;
	}

	public MineTask mineTask() {
		return mineTask;
	}

	public RtpTask rtpTask() {
		return rtpTask;
	}

	public Set<Item> unsellableItems() {
		return unsellableItems;
	}

	public StopKind stopKind() {
		return stopKind;
	}

	public String stopReason() {
		return stopReason;
	}

	public long stopTime() {
		return stopTime;
	}

	public boolean isPayeeOnline() {
		return payeeOnline;
	}

	public boolean isAttacking() {
		return running && attacking;
	}

	public void setAttacking(boolean value) {
		attacking = value;
	}

	public boolean isEating() {
		return running && survival.isEating();
	}

	public long cycleDurationMs() {
		return ConfigManager.get().cycleSeconds * 1000L;
	}

	public long cycleElapsedMs() {
		return running ? Util.getMillis() - cycleStart : 0;
	}

	public long cycleRemainingMs() {
		return Math.max(0, cycleDurationMs() - cycleElapsedMs());
	}

	public float cycleProgress() {
		if (!running) return 0f;
		return Math.min(1f, cycleElapsedMs() / (float) cycleDurationMs());
	}

	public boolean cycleExpired() {
		return cycleElapsedMs() >= cycleDurationMs();
	}

	/** 0 = RTP, 1 = scan, 2 = mine, 3 = sell, 4 = pay, -1 = idle. */
	public int pipelineStep() {
		if (!running) return -1;
		return switch (phase) {
			case SEARCHING -> rtpTask.state() == RtpTask.State.CHUNKS || rtpTask.state() == RtpTask.State.SCAN ? 1 : 0;
			case MINING, WAITING -> 2;
			case SELLING -> 3;
			case PAYING -> 4;
			default -> -1;
		};
	}

	public String detail() {
		if (!running) {
			if (Reconnector.isWaiting()) {
				return "Disconnected — reconnecting in " + Reconnector.secondsUntilReconnect() + "s";
			}
			if (respawnPending) {
				return "Died — respawning and restarting";
			}
			return stopKind == null || stopKind == StopKind.USER ? "Stopped — press Start to begin" : "Stopped: " + stopReason;
		}
		if (paused) {
			return "Paused" + (pauseReason.isEmpty() ? "" : ": " + pauseReason);
		}
		if (survival.isActive()) {
			return survival.status();
		}
		return switch (phase) {
			case SEARCHING -> rtpTask.detail();
			case MINING -> mineTask.detail();
			case SELLING -> sellTask.detail();
			case PAYING -> payTask.detail();
			case WAITING -> waitReason + " — selling in " + TextUtil.clock(cycleRemainingMs() / 1000);
			default -> "";
		};
	}
}
