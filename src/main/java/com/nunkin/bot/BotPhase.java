package com.nunkin.bot;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum BotPhase {
	IDLE("Idle", 0xFF9CA3AF, Items.BARRIER),
	SEARCHING("Searching", 0xFF60A5FA, Items.ENDER_PEARL),
	MINING("Mining", 0xFFE8C872, Items.DIAMOND_SHOVEL),
	SELLING("Selling", 0xFF34D399, Items.EMERALD),
	PAYING("Paying", 0xFFA78BFA, Items.GOLD_INGOT),
	WAITING("Waiting", 0xFF9CA3AF, Items.CLOCK);

	public final String label;
	public final int color;
	public final Item icon;

	BotPhase(String label, int color, Item icon) {
		this.label = label;
		this.color = color;
		this.icon = icon;
	}
}
