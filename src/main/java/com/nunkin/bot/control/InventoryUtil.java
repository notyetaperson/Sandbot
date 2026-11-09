package com.nunkin.bot.control;

import com.nunkin.config.BotConfig;
import com.nunkin.util.TextUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class InventoryUtil {
	private static final BlockState SAND = Blocks.SAND.defaultBlockState();

	private InventoryUtil() {
	}

	public static boolean isSand(ItemStack stack, boolean includeRed) {
		return stack.is(Items.SAND) || (includeRed && stack.is(Items.RED_SAND));
	}

	public static int freeSlots(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int free = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/** True if picking up {@code stack} would put at least part of it into the inventory. */
	public static boolean canFit(LocalPlayer player, ItemStack stack) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack slot = inv.getItem(i);
			if (slot.isEmpty() || (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize())) {
				return true;
			}
		}
		return false;
	}

	/** Comma separated keywords matched against the item's name and lore; blank never matches. */
	public static boolean matchesKeywords(ItemStack stack, String keywords) {
		if (keywords == null || keywords.isBlank()) {
			return false;
		}
		String text = itemText(stack);
		for (String part : keywords.split(",")) {
			String word = TextUtil.normalize(part);
			if (!word.isEmpty() && text.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** Never sold or thrown away: tools, weapons and armor, food, and anything on the keep list. */
	public static boolean isProtected(ItemStack stack, BotConfig cfg) {
		return stack.isDamageableItem() || stack.has(DataComponents.FOOD) || matchesKeywords(stack, cfg.keepItems);
	}

	public static boolean isSellable(ItemStack stack, BotConfig cfg, Set<Item> unsellable) {
		if (stack.isEmpty()) {
			return false;
		}
		if (isSand(stack, cfg.includeRedSand)) {
			return true;
		}
		return cfg.sellLoot && !isProtected(stack, cfg) && !unsellable.contains(stack.getItem());
	}

	public static int countSellable(LocalPlayer player, BotConfig cfg, Set<Item> unsellable) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (isSellable(stack, cfg, unsellable)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static Map<Item, Integer> sellableCounts(LocalPlayer player, BotConfig cfg, Set<Item> unsellable) {
		Map<Item, Integer> counts = new HashMap<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (isSellable(stack, cfg, unsellable)) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	/** Slot index inside the player's own inventory menu for inventory slot {@code invSlot} (0-35). */
	public static int inventoryMenuSlot(int invSlot) {
		return invSlot < Inventory.SELECTION_SIZE ? 36 + invSlot : invSlot;
	}

	public static int countSand(LocalPlayer player, boolean includeRed) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (isSand(stack, includeRed)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Full = no empty slot and no sand stack with room left. */
	public static boolean isFull(LocalPlayer player, boolean includeRed) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				return false;
			}
			if (isSand(stack, includeRed) && stack.getCount() < stack.getMaxStackSize()) {
				return false;
			}
		}
		return true;
	}

	public static int sandCapacity(LocalPlayer player, boolean includeRed) {
		Inventory inv = player.getInventory();
		int capacity = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || isSand(stack, includeRed)) {
				capacity += 64;
			}
		}
		return capacity;
	}

	public static int remainingDurability(ItemStack stack) {
		return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
	}

	/** Best shovel-like tool for sand in the main inventory or hotbar, or -1. */
	public static int findBestTool(LocalPlayer player, int minDurability) {
		Inventory inv = player.getInventory();
		Optional<Holder.Reference<Enchantment>> efficiency = player.level().registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.EFFICIENCY);
		int best = -1;
		float bestScore = 1.0f;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			float speed = stack.getDestroySpeed(SAND);
			if (speed <= 1.0f) continue;
			if (stack.isDamageableItem() && remainingDurability(stack) <= minDurability) continue;
			int eff = efficiency.map(e -> EnchantmentHelper.getItemEnchantmentLevel(e, stack)).orElse(0);
			float score = speed + (eff > 0 ? eff * eff + 1 : 0) + (i < 9 ? 0.01f : 0f);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	public static boolean isGoodFood(ItemStack stack) {
		if (stack.isEmpty()) return false;
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) return false;
		return !(stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
			|| stack.is(Items.PUFFERFISH) || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.SUSPICIOUS_STEW));
	}

	public static int findFood(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int best = -1;
		int bestScore = -1;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (!isGoodFood(stack)) continue;
			FoodProperties food = stack.get(DataComponents.FOOD);
			int score = food.nutrition() * 10 + (i < 9 ? 5 : 0);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/**
	 * Makes sure the item in inventory slot {@code invSlot} is in the hotbar and selected.
	 * Returns true when it is selected; false when a swap was just requested (try again next tick).
	 */
	public static boolean select(Minecraft mc, LocalPlayer player, int invSlot) {
		Inventory inv = player.getInventory();
		if (invSlot < 0) return false;
		if (invSlot < Inventory.SELECTION_SIZE) {
			if (inv.getSelectedSlot() != invSlot) {
				inv.setSelectedSlot(invSlot);
			}
			return true;
		}
		if (player.containerMenu != player.inventoryMenu || mc.gameMode == null) {
			return false;
		}
		int hotbar = pickHotbarSlot(player);
		mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, invSlot, hotbar, ClickType.SWAP, player);
		inv.setSelectedSlot(hotbar);
		return false;
	}

	private static int pickHotbarSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
			if (isSand(inv.getItem(i), true)) return i;
		}
		return inv.getSelectedSlot();
	}

	public static boolean isPlayerSlot(Slot slot, LocalPlayer player) {
		return slot.container == player.getInventory();
	}

	public static String itemText(ItemStack stack) {
		StringBuilder sb = new StringBuilder(stack.getHoverName().getString());
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				sb.append(' ').append(line.getString());
			}
		}
		return TextUtil.normalize(sb.toString());
	}

	/** First non-player slot whose name or lore contains one of the comma separated keywords. */
	public static Slot findMenuSlot(AbstractContainerMenu menu, LocalPlayer player, String keywords) {
		List<String> words = new ArrayList<>();
		for (String part : keywords.split(",")) {
			String w = TextUtil.normalize(part);
			if (!w.isEmpty()) words.add(w);
		}
		if (words.isEmpty()) return null;
		for (String word : words) {
			for (Slot slot : menu.slots) {
				if (isPlayerSlot(slot, player) || !slot.hasItem()) continue;
				if (TextUtil.normalize(slot.getItem().getHoverName().getString()).contains(word)) {
					return slot;
				}
			}
			for (Slot slot : menu.slots) {
				if (isPlayerSlot(slot, player) || !slot.hasItem()) continue;
				if (itemText(slot.getItem()).contains(word)) {
					return slot;
				}
			}
		}
		return null;
	}

	public static List<String> describeMenu(AbstractContainerMenu menu, LocalPlayer player) {
		List<String> names = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (isPlayerSlot(slot, player) || !slot.hasItem()) continue;
			String name = TextUtil.normalize(slot.getItem().getHoverName().getString());
			if (!name.isBlank() && !names.contains(name) && names.size() < 12) {
				names.add(name);
			}
		}
		return names;
	}

	public static boolean hasForeignContainerOpen(LocalPlayer player) {
		return player.containerMenu != null && player.containerMenu != player.inventoryMenu;
	}
}
