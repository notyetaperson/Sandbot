package com.nunkin.bot.task;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.ChatWatcher;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.util.TextUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reads the balance, pays it to the configured player and confirms the payment by reading the balance again.
 * If the server refuses because the recipient is offline, the payout is reported as {@link Status#DEFERRED}.
 */
public final class PayTask {
	public enum Status {
		RUNNING, PAID, DEFERRED, NOTHING, FAILED
	}

	private enum State {
		QUERY, WAIT_BALANCE, SEND, WAIT_RESULT, VERIFY, WAIT_VERIFY
	}

	private static final int BALANCE_TIMEOUT = 80;
	private static final int RESULT_TIMEOUT = 100;

	private final SandBot bot;
	private State state = State.QUERY;
	private long stateStart;
	private long chatMark;
	private TextUtil.Money balanceBefore;
	private double amount;
	private int retries;
	private boolean confirmClicked;
	private boolean sawSuccess;
	private String offlineReply;
	private String detail = "";

	public PayTask(SandBot bot) {
		this.bot = bot;
	}

	public void begin() {
		setState(State.QUERY);
		retries = 0;
		balanceBefore = null;
		amount = 0;
		detail = "Checking balance";
	}

	public String detail() {
		return detail;
	}

	/** The amount of the last payout attempt. */
	public double amount() {
		return amount;
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
		String target = cfg.payTarget.trim();
		switch (state) {
			case QUERY -> {
				if (!cfg.payEnabled) {
					detail = "Payouts disabled";
					return Status.NOTHING;
				}
				if (target.isEmpty()) {
					BotLog.warn("No payout player configured — set one in Settings > Payout");
					detail = "No payout player set";
					return Status.NOTHING;
				}
				if (target.equalsIgnoreCase(player.getGameProfile().name())) {
					BotLog.error("The payout player is this account itself — set your main account in Settings > Payout");
					detail = "Payout player is the bot itself";
					return Status.NOTHING;
				}
				bot.closeForeignContainer(player);
				requestBalance(cfg);
				detail = "Checking balance";
				setState(State.WAIT_BALANCE);
			}
			case WAIT_BALANCE -> {
				if (rejected(cfg.balanceCommand)) {
					return Status.FAILED;
				}
				TextUtil.Money balance = pollBalance(level);
				if (balance == null) {
					if (elapsed() > BALANCE_TIMEOUT) {
						BotLog.warn("Could not read your balance from /" + TextUtil.stripSlash(cfg.balanceCommand) + " or the scoreboard");
						return Status.FAILED;
					}
					detail = "Waiting for balance";
					break;
				}
				balanceBefore = balance;
				amount = Math.floor(balance.value() - cfg.keepBalance);
				if (amount < Math.max(1, cfg.minPayout)) {
					BotLog.info("Balance " + TextUtil.money(balance.value()) + " is below the minimum payout, nothing sent");
					detail = "Nothing to pay";
					return Status.NOTHING;
				}
				setState(State.SEND);
			}
			case SEND -> {
				String command = TextUtil.stripSlash(cfg.payCommand)
					.replace("{player}", target)
					.replace("{amount}", TextUtil.plainAmount(amount));
				chatMark = ChatWatcher.mark();
				confirmClicked = false;
				sawSuccess = false;
				offlineReply = null;
				bot.sendCommand(command);
				BotLog.info("Paying " + TextUtil.money(amount) + " to " + target);
				detail = "Paying " + TextUtil.money(amount) + " to " + target;
				setState(State.WAIT_RESULT);
			}
			case WAIT_RESULT -> {
				clickConfirm(mc, player, cfg);
				for (ChatWatcher.Line line : ChatWatcher.since(chatMark)) {
					if (line.fromPlayer()) continue;
					if (line.commandError()) {
						BotLog.error("Server rejected the pay command: \"" + line.raw() + "\" — check the pay command in Settings");
						return Status.FAILED;
					}
					String norm = line.norm();
					if (TextUtil.containsAny(norm, "insufficient", "not enough", "don't have enough", "do not have enough", "cannot afford", "can't afford")) {
						double step = Math.max(1, Math.ceil(balanceBefore.unit()));
						if (retries++ < 4 && amount - step >= Math.max(1, cfg.minPayout)) {
							amount -= step;
							BotLog.info("Retrying payout with " + TextUtil.money(amount));
							setState(State.SEND);
							return Status.RUNNING;
						}
						return Status.FAILED;
					}
					if (norm.contains("yourself")) {
						BotLog.error("Server says you can't pay yourself — set your main account in Settings > Payout");
						return Status.FAILED;
					}
					boolean success = isSuccess(norm);
					if (success) {
						sawSuccess = true;
					} else if (isRecipientUnavailable(norm)) {
						offlineReply = line.raw();
					}
				}
				if (offlineReply != null && !sawSuccess) {
					BotLog.warn(target + " can't be paid right now (\"" + offlineReply + "\") — holding " + TextUtil.money(amount));
					detail = target + " is offline";
					return Status.DEFERRED;
				}
				if (sawSuccess || elapsed() > RESULT_TIMEOUT) {
					setState(State.VERIFY);
				} else {
					detail = "Waiting for payment confirmation";
				}
			}
			case VERIFY -> {
				bot.closeForeignContainer(player);
				requestBalance(cfg);
				detail = "Confirming payment";
				setState(State.WAIT_VERIFY);
			}
			case WAIT_VERIFY -> {
				TextUtil.Money after = rejected(cfg.balanceCommand) ? null : pollBalance(level);
				if (after == null && elapsed() <= BALANCE_TIMEOUT) {
					break;
				}
				if (after != null && after.value() <= balanceBefore.value() - amount * 0.5) {
					return recordPaid(target);
				}
				if (after == null && sawSuccess) {
					BotLog.warn("Couldn't re-check the balance; trusting the server's payment message");
					return recordPaid(target);
				}
				if (after != null) {
					BotLog.warn("Payment to " + target + " did not go through (balance still " + TextUtil.money(after.value()) + ")");
				} else {
					BotLog.warn("Payment to " + target + " could not be confirmed");
				}
				return Status.FAILED;
			}
		}
		return Status.RUNNING;
	}

	private Status recordPaid(String target) {
		bot.stats().addPaid(amount);
		BotLog.success("Paid " + TextUtil.money(amount) + " to " + target);
		detail = "Paid " + TextUtil.money(amount);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && InventoryUtil.hasForeignContainerOpen(mc.player)) {
			mc.player.closeContainer();
		}
		return Status.PAID;
	}

	private void requestBalance(BotConfig cfg) {
		chatMark = ChatWatcher.mark();
		bot.sendCommand(TextUtil.stripSlash(cfg.balanceCommand));
	}

	private boolean rejected(String command) {
		ChatWatcher.Line error = ChatWatcher.commandErrorSince(chatMark);
		if (error == null) {
			return false;
		}
		BotLog.error("Server rejected /" + TextUtil.stripSlash(command) + ": \"" + error.raw() + "\" — check the balance command in Settings");
		return true;
	}

	/** Balance from the reply to the balance command, falling back to the scoreboard sidebar. */
	private TextUtil.Money pollBalance(ClientLevel level) {
		for (ChatWatcher.Line line : ChatWatcher.since(chatMark)) {
			if (line.fromPlayer() || line.commandError()) continue;
			String norm = line.norm();
			if (!TextUtil.containsAny(norm, "balance", "bal", "money", "you have", "$", "coins", "purse")) continue;
			TextUtil.Money money = TextUtil.findMoney(norm, TextUtil.containsAny(norm, "balance", "money", "you have"));
			if (money != null) {
				return money;
			}
		}
		if (elapsed() > BALANCE_TIMEOUT) {
			TextUtil.Money sidebar = readSidebarBalance(level);
			if (sidebar != null) {
				BotLog.info("Read balance from the scoreboard: " + TextUtil.money(sidebar.value()));
			}
			return sidebar;
		}
		return null;
	}

	private void clickConfirm(Minecraft mc, LocalPlayer player, BotConfig cfg) {
		if (confirmClicked || !InventoryUtil.hasForeignContainerOpen(player) || mc.gameMode == null || elapsed() <= 3) {
			return;
		}
		Slot confirm = InventoryUtil.findMenuSlot(player.containerMenu, player, cfg.payConfirmKeyword);
		if (confirm != null) {
			mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, confirm.index, 0, ClickType.PICKUP, player);
			BotLog.info("Confirmed payment in menu");
		}
		confirmClicked = true;
	}

	private static boolean isSuccess(String norm) {
		return TextUtil.containsAny(norm, "you paid", "you have paid", "you've paid", "paid $", "you sent", "you have sent", "you've sent",
			"has been sent", "was sent", "successfully", "transferred", "you gave", "you have given");
	}

	private static boolean isRecipientUnavailable(String norm) {
		return TextUtil.containsAny(norm, "not online", "isn't online", "is offline", "are offline", "offline player", "not found",
			"no player", "unknown player", "invalid player", "never joined", "never played", "doesn't exist", "does not exist",
			"can't pay", "cannot pay", "could not find");
	}

	/** Looks for a sidebar line mentioning money/balance and parses the amount from it. */
	private static TextUtil.Money readSidebarBalance(ClientLevel level) {
		Scoreboard scoreboard = level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			return null;
		}
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) continue;
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component name = PlayerTeam.formatNameForTeam(team, entry.ownerName());
			String text = TextUtil.normalize(name.getString() + " " + entry.formatValue(objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)).getString());
			if (TextUtil.containsAny(text, "money", "balance", "bal", "$", "coins")) {
				TextUtil.Money money = TextUtil.findMoney(text, true);
				if (money != null) {
					return money;
				}
			}
		}
		return null;
	}
}
