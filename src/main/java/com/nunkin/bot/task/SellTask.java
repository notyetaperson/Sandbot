package com.nunkin.bot.task;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.ChatWatcher;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.util.TextUtil;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Sells sand (and loot, if enabled): opens the /sell GUI, shift-clicks every sellable stack into it and closes it,
 * or just runs a command. Item types the server hands back are remembered and not offered again.
 */
public final class SellTask {
	public enum Status {
		RUNNING, DONE, FAILED
	}

	private enum State {
		OPEN, WAIT_GUI, TRANSFER, CONFIRM, RESULT
	}

	private static final Pattern SOLD_COUNT = Pattern.compile("(?<![$.\\d])(\\d[\\d,]*)\\s*x?\\s*(?:items?|sand|blocks?)\\b");

	private final SandBot bot;
	private final Set<Item> moved = new HashSet<>();
	private State state = State.OPEN;
	private long stateStart;
	private int attempts;
	private int clickCooldown;
	private int lastClickedSlot = -1;
	private int sameSlotClicks;
	private long chatMark;
	private int countBefore;
	private Map<Item, Integer> countsBefore = Map.of();
	private boolean confirmClicked;
	private boolean soldAnything;
	private String detail = "";

	public SellTask(SandBot bot) {
		this.bot = bot;
	}

	public void begin() {
		state = State.OPEN;
		stateStart = bot.ticks();
		attempts = 0;
		soldAnything = false;
		detail = "Preparing to sell";
	}

	public String detail() {
		return detail;
	}

	/** Whether the last run sold at least one item. */
	public boolean soldAnything() {
		return soldAnything;
	}

	private void setState(State next) {
		state = next;
		stateStart = bot.ticks();
	}

	private long elapsed() {
		return bot.ticks() - stateStart;
	}

	public Status tick(Minecraft mc, LocalPlayer player) {
		BotConfig cfg = ConfigManager.get();
		Set<Item> unsellable = bot.unsellableItems();
		switch (state) {
			case OPEN -> {
				int count = InventoryUtil.countSellable(player, cfg, unsellable);
				if (count == 0) {
					detail = "Nothing to sell";
					if (attempts == 0) {
						BotLog.info("Nothing to sell, skipping /sell");
					}
					return Status.DONE;
				}
				if (attempts >= 4) {
					BotLog.warn("Still holding " + count + " sellable items after " + attempts + " sell attempts");
					return Status.FAILED;
				}
				bot.closeForeignContainer(player);
				countBefore = count;
				countsBefore = InventoryUtil.sellableCounts(player, cfg, unsellable);
				moved.clear();
				chatMark = ChatWatcher.mark();
				attempts++;
				lastClickedSlot = -1;
				sameSlotClicks = 0;
				confirmClicked = false;
				String command = TextUtil.stripSlash(cfg.sellCommand);
				bot.sendCommand(command);
				BotLog.info("Selling " + count + " items (/" + command + ")");
				detail = "Opening /" + command;
				setState(cfg.sellMode == BotConfig.SellMode.GUI ? State.WAIT_GUI : State.RESULT);
			}
			case WAIT_GUI -> {
				if (rejected(cfg)) {
					return Status.FAILED;
				}
				if (mc.screen instanceof AbstractContainerScreen<?> && InventoryUtil.hasForeignContainerOpen(player)) {
					if (elapsed() >= 4) {
						clickCooldown = 0;
						setState(State.TRANSFER);
					}
					detail = "Sell menu opened";
					break;
				}
				if (InventoryUtil.countSellable(player, cfg, unsellable) < countBefore) {
					setState(State.RESULT);
					break;
				}
				if (elapsed() > 100) {
					BotLog.warn("/sell did not open a menu, retrying");
					setState(State.OPEN);
				}
			}
			case TRANSFER -> {
				if (!InventoryUtil.hasForeignContainerOpen(player) || mc.gameMode == null) {
					setState(State.RESULT);
					break;
				}
				if (clickCooldown-- > 0) {
					break;
				}
				AbstractContainerMenu menu = player.containerMenu;
				Slot next = null;
				for (Slot slot : menu.slots) {
					if (InventoryUtil.isPlayerSlot(slot, player) && InventoryUtil.isSellable(slot.getItem(), cfg, unsellable)) {
						next = slot;
						break;
					}
				}
				if (next != null && next.index == lastClickedSlot && ++sameSlotClicks > 2) {
					BotLog.info("Sell menu is full, closing it to sell this batch");
					next = null;
				}
				if (next == null) {
					finishTransfer(player, cfg);
					break;
				}
				if (next.index != lastClickedSlot) {
					sameSlotClicks = 0;
				}
				moved.add(next.getItem().getItem());
				mc.gameMode.handleInventoryMouseClick(menu.containerId, next.index, 0, ClickType.QUICK_MOVE, player);
				lastClickedSlot = next.index;
				clickCooldown = cfg.sellClickDelayTicks;
				detail = "Moving items into the sell menu";
			}
			case CONFIRM -> {
				if (!InventoryUtil.hasForeignContainerOpen(player)) {
					setState(State.RESULT);
					break;
				}
				if (!confirmClicked && mc.gameMode != null) {
					Slot confirm = InventoryUtil.findMenuSlot(player.containerMenu, player, cfg.sellConfirmKeyword);
					if (confirm != null) {
						mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, confirm.index, 0, ClickType.PICKUP, player);
						BotLog.info("Clicked sell confirm button");
					}
					confirmClicked = true;
				}
				if (elapsed() > 20) {
					player.closeContainer();
					setState(State.RESULT);
				}
			}
			case RESULT -> {
				detail = "Waiting for sale confirmation";
				if (rejected(cfg)) {
					return Status.FAILED;
				}
				ChatWatcher.Line line = ChatWatcher.findSince(chatMark, l -> !l.fromPlayer() && isSaleMessage(l.norm()));
				if (line == null && elapsed() < 60) {
					break;
				}
				int remaining = InventoryUtil.countSellable(player, cfg, unsellable);
				int sold = Math.max(0, countBefore - remaining);
				double money = 0;
				if (line != null) {
					TextUtil.Money parsed = TextUtil.findMoney(line.norm(), false);
					if (parsed != null) {
						money = parsed.value();
					}
					Matcher count = SOLD_COUNT.matcher(line.norm());
					if (count.find()) {
						sold = Integer.parseInt(count.group(1).replace(",", ""));
					}
				}
				if (sold > 0) {
					soldAnything = true;
					learnUnsellable(player, cfg);
					remaining = InventoryUtil.countSellable(player, cfg, unsellable);
					bot.stats().addSold(sold, money);
					BotLog.success("Sold " + sold + " items" + (money > 0 ? " for " + TextUtil.money(money) : ""));
				} else if (line == null) {
					BotLog.warn("No sale detected (inventory unchanged)");
				}
				if (cfg.dropUnsellable) {
					dropUnsellable(mc, player, cfg);
				}
				if (remaining > 0 && sold > 0) {
					setState(State.OPEN);
					break;
				}
				return sold > 0 || remaining == 0 ? Status.DONE : Status.FAILED;
			}
		}
		return Status.RUNNING;
	}

	/** Item types that went into the menu but came back are ones the server won't buy. */
	private void learnUnsellable(LocalPlayer player, BotConfig cfg) {
		Set<Item> unsellable = bot.unsellableItems();
		Map<Item, Integer> after = InventoryUtil.sellableCounts(player, cfg, unsellable);
		for (Item item : moved) {
			if (item == Items.SAND || item == Items.RED_SAND) continue;
			int before = countsBefore.getOrDefault(item, 0);
			if (before > 0 && after.getOrDefault(item, 0) >= before) {
				unsellable.add(item);
				BotLog.info("The server didn't buy " + new ItemStack(item).getHoverName().getString() + ", keeping it from now on");
			}
		}
	}

	private void dropUnsellable(Minecraft mc, LocalPlayer player, BotConfig cfg) {
		if (mc.gameMode == null || InventoryUtil.hasForeignContainerOpen(player)) {
			return;
		}
		Set<Item> unsellable = bot.unsellableItems();
		Inventory inv = player.getInventory();
		int dropped = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !unsellable.contains(stack.getItem()) || InventoryUtil.isProtected(stack, cfg)) continue;
			dropped += stack.getCount();
			mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, InventoryUtil.inventoryMenuSlot(i), 1, ClickType.THROW, player);
		}
		if (dropped > 0) {
			BotLog.info("Dropped " + dropped + " items the server won't buy");
		}
	}

	private void finishTransfer(LocalPlayer player, BotConfig cfg) {
		if (!cfg.sellConfirmKeyword.isBlank()) {
			confirmClicked = false;
			setState(State.CONFIRM);
		} else {
			player.closeContainer();
			setState(State.RESULT);
		}
		detail = "Selling";
	}

	private boolean rejected(BotConfig cfg) {
		ChatWatcher.Line error = ChatWatcher.commandErrorSince(chatMark);
		if (error == null) {
			return false;
		}
		BotLog.error("Server rejected /" + TextUtil.stripSlash(cfg.sellCommand) + ": \"" + error.raw() + "\" — check the sell command in Settings");
		detail = "Sell command rejected";
		return true;
	}

	private static boolean isSaleMessage(String norm) {
		return TextUtil.containsAny(norm, "sold", "you sell", "sale", "earned", "received") || (norm.contains("+") && norm.contains("$"));
	}
}
