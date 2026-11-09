package com.nunkin.bot.task;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.ChatWatcher;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.world.SandScanner;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.util.TextUtil;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Random-teleports until the area within the scan radius has more exposed sand than the threshold. */
public final class RtpTask {
	public enum Status {
		RUNNING, FOUND
	}

	public enum State {
		PREPARE("Getting ready to teleport"),
		SEND("Sending RTP command"),
		MENU("Waiting for RTP menu"),
		TELEPORT("Waiting for teleport"),
		SETTLE("Landing"),
		CHUNKS("Loading chunks"),
		SCAN("Scanning for sand");

		public final String label;

		State(String label) {
			this.label = label;
		}
	}

	private final SandBot bot;
	private State state = State.PREPARE;
	private long stateStart;
	private long cooldownUntil;
	private Vec3 origin;
	private ResourceKey<Level> originDimension;
	private long chatMark;
	private boolean menuClicked;
	private long menuSeenAt = -1;
	private int attempts;
	private int lastLoaded;
	private long lastLoadedChange;
	private String detail = "";

	public RtpTask(SandBot bot) {
		this.bot = bot;
	}

	public void begin() {
		setState(State.PREPARE);
		attempts = 0;
		detail = "Preparing to teleport";
	}

	public State state() {
		return state;
	}

	public int attempts() {
		return attempts;
	}

	public String detail() {
		return detail;
	}

	/** True while a teleport is in flight and the bot should not start other actions. */
	public boolean isBusy() {
		return state == State.MENU || state == State.TELEPORT || state == State.SETTLE;
	}

	private void setState(State next) {
		state = next;
		stateStart = bot.ticks();
	}

	private long elapsed() {
		return bot.ticks() - stateStart;
	}

	public Status tick(Minecraft mc, LocalPlayer player, ClientLevel level) {
		BotConfig cfg = ConfigManager.get();
		long now = bot.ticks();
		switch (state) {
			case PREPARE -> {
				bot.closeForeignContainer(player);
				if (now >= cooldownUntil) {
					setState(State.SEND);
				} else {
					detail = "Next RTP in " + TextUtil.clock((cooldownUntil - now + 19) / 20);
				}
			}
			case SEND -> {
				origin = player.position();
				originDimension = level.dimension();
				chatMark = ChatWatcher.mark();
				attempts++;
				bot.stats().addRtp();
				menuClicked = false;
				menuSeenAt = -1;
				String command = TextUtil.stripSlash(cfg.rtpCommand);
				if (command.isEmpty()) {
					command = "rtp";
				}
				bot.sendCommand(command);
				BotLog.info("RTP attempt #" + attempts + " (/" + command + ")");
				detail = "Sent /" + command + " (attempt #" + attempts + ")";
				setState(State.MENU);
			}
			case MENU -> {
				if (hasTeleported(player, level)) {
					onTeleported();
					break;
				}
				if (handleMenu(mc, player, cfg)) {
					break;
				}
				if (checkFailure(cfg)) {
					break;
				}
				if (elapsed() > 40) {
					setState(State.TELEPORT);
				}
				detail = "Waiting for teleport (" + attempts + ")";
			}
			case TELEPORT -> {
				if (hasTeleported(player, level)) {
					onTeleported();
					break;
				}
				if (handleMenu(mc, player, cfg)) {
					break;
				}
				if (checkFailure(cfg)) {
					break;
				}
				long left = cfg.rtpTimeoutSeconds * 20L - elapsed();
				detail = "Waiting for teleport — timeout in " + Math.max(0, left / 20) + "s";
				if (left <= 0) {
					BotLog.warn("RTP timed out after " + cfg.rtpTimeoutSeconds + "s, retrying");
					retryLater(cfg.rtpDelaySeconds);
				}
			}
			case SETTLE -> {
				detail = "Landing at " + player.blockPosition().toShortString();
				if (elapsed() >= 20 && (player.onGround() || player.isInWater() || elapsed() > 100)) {
					lastLoaded = -1;
					setState(State.CHUNKS);
				}
			}
			case CHUNKS -> {
				int radius = cfg.scanRadiusChunks;
				int total = SandScanner.chunkCount(radius);
				int loaded = SandScanner.countLoadedChunks(level, player.chunkPosition(), radius);
				if (loaded != lastLoaded) {
					lastLoaded = loaded;
					lastLoadedChange = now;
				}
				detail = "Loading chunks " + loaded + "/" + total;
				boolean stable = now - lastLoadedChange > 30 && elapsed() > 40;
				if (loaded >= total || stable || elapsed() > cfg.chunkWaitSeconds * 20L) {
					if (loaded < total) {
						BotLog.debug("Scanning with " + loaded + "/" + total + " chunks loaded (render distance limits the scan)");
					}
					setState(State.SCAN);
				}
			}
			case SCAN -> {
				ScanResult scan = bot.rescan();
				int count = scan == null ? 0 : scan.exposedCount;
				if (scan != null && count > cfg.sandThreshold) {
					BotLog.success("Found " + TextUtil.number(count) + " sky-exposed sand after " + attempts + " RTP(s)");
					detail = "Found " + count + " exposed sand";
					setState(State.PREPARE);
					cooldownUntil = 0;
					return Status.FOUND;
				}
				BotLog.info("Only " + count + " exposed sand here (need > " + cfg.sandThreshold + "), trying another spot");
				detail = "Only " + count + " sand here — re-rolling";
				retryLater(cfg.rtpDelaySeconds);
			}
		}
		return Status.RUNNING;
	}

	private void onTeleported() {
		BotLog.info("Teleported to " + Minecraft.getInstance().player.blockPosition().toShortString());
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && InventoryUtil.hasForeignContainerOpen(mc.player)) {
			mc.player.closeContainer();
		}
		setState(State.SETTLE);
	}

	private void retryLater(int seconds) {
		cooldownUntil = bot.ticks() + Math.max(0, seconds) * 20L;
		setState(State.PREPARE);
	}

	private boolean hasTeleported(LocalPlayer player, ClientLevel level) {
		if (origin == null) {
			return false;
		}
		if (originDimension != null && level.dimension() != originDimension) {
			return true;
		}
		return player.position().distanceTo(origin) > 48;
	}

	/** Clicks the configured option if the RTP command opened a chest-style menu. */
	private boolean handleMenu(Minecraft mc, LocalPlayer player, BotConfig cfg) {
		if (!(mc.screen instanceof AbstractContainerScreen<?>) || !InventoryUtil.hasForeignContainerOpen(player) || mc.gameMode == null) {
			menuSeenAt = -1;
			return false;
		}
		long now = bot.ticks();
		if (menuSeenAt < 0) {
			menuSeenAt = now;
		}
		if (now - menuSeenAt < 5) {
			detail = "RTP menu opened";
			return true;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (!menuClicked) {
			Slot slot = InventoryUtil.findMenuSlot(menu, player, cfg.rtpMenuKeyword);
			if (slot != null) {
				String name = TextUtil.normalize(slot.getItem().getHoverName().getString());
				mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, player);
				menuClicked = true;
				BotLog.info("Clicked \"" + name + "\" in the RTP menu");
				detail = "Selected RTP option";
				setState(State.TELEPORT);
				return true;
			}
			List<String> options = InventoryUtil.describeMenu(menu, player);
			BotLog.warn("RTP menu has no option matching \"" + cfg.rtpMenuKeyword + "\". Options: " + String.join(", ", options));
			player.closeContainer();
			retryLater(Math.max(cfg.rtpDelaySeconds, 5));
			return true;
		}
		if (now - menuSeenAt > 30) {
			player.closeContainer();
		}
		return true;
	}

	/** Detects cooldown / refusal messages from the server after the RTP command. */
	private boolean checkFailure(BotConfig cfg) {
		ChatWatcher.Line line = ChatWatcher.findSince(chatMark, l -> !l.fromPlayer() && (l.commandError() || isFailureMessage(l.norm())));
		if (line == null) {
			return false;
		}
		chatMark = line.id() + 1;
		if (line.commandError()) {
			int wait = Math.max(cfg.rtpDelaySeconds, 10);
			BotLog.error("Server rejected /" + TextUtil.stripSlash(cfg.rtpCommand) + ": \"" + line.raw() + "\" — check the RTP command in Settings");
			retryLater(wait);
			return true;
		}
		int seconds = TextUtil.parseDurationSeconds(line.norm());
		int wait = seconds > 0 ? Math.min(seconds + 1, 900) : Math.max(cfg.rtpDelaySeconds, 5);
		BotLog.warn("RTP refused: \"" + line.raw() + "\" — retrying in " + wait + "s");
		retryLater(wait);
		return true;
	}

	private static boolean isFailureMessage(String norm) {
		if (TextUtil.containsAny(norm, "teleporting in", "teleport in", "don't move", "do not move", "teleported")) {
			return false;
		}
		return norm.contains("cooldown")
			|| (norm.contains("wait") && TextUtil.containsAny(norm, "before", "again", "until", "more"))
			|| norm.contains("try again")
			|| TextUtil.containsAny(norm, "can't use", "cannot use", "can't rtp", "cannot rtp", "in combat", "not allowed", "no permission",
			"unknown command", "failed to find", "could not find a safe");
	}
}
