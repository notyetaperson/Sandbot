package com.nunkin.ui;

import com.nunkin.bot.BotPhase;
import com.nunkin.bot.Notifications;
import com.nunkin.bot.Reconnector;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.util.TextUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Compact in-game status panel plus toast notifications. */
public final class HudOverlay {
	private static final int W = 172;
	private static final int H = 64;

	private HudOverlay() {
	}

	public static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.player == null) {
			return;
		}
		boolean panelOpen = mc.screen instanceof BotScreen;
		BotConfig cfg = ConfigManager.get();
		SandBot bot = SandBot.get();
		int sw = g.guiWidth();
		int sh = g.guiHeight();
		int toastY = 6;

		if (cfg.showHud && bot.isRunning() && !panelOpen && !mc.getDebugOverlay().showDebugScreen()) {
			float scale = (float) cfg.hudScale;
			int sx = (int) (W * scale);
			int sy = (int) (H * scale);
			int x = cfg.hudCorner == BotConfig.HudCorner.TOP_LEFT ? 6 : sw - sx - 6;
			int y = cfg.hudCorner == BotConfig.HudCorner.BOTTOM_RIGHT ? sh - sy - 30 : 6;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			drawPanel(g, mc.font, bot, cfg);
			g.pose().popMatrix();
			if (cfg.hudCorner == BotConfig.HudCorner.TOP_RIGHT) {
				toastY = y + sy + 4;
			}
		} else if (Reconnector.isWaiting() && !panelOpen) {
			String text = "SandBot: reconnecting in " + Reconnector.secondsUntilReconnect() + "s";
			Theme.card(g, 6, 6, mc.font.width(text) + 12, 16, 0xE0101217, Theme.LINE);
			Theme.text(g, mc.font, text, 12, 10, Theme.WARN);
		}
		if (!panelOpen) {
			Notifications.render(g, mc.font, sw - 6, toastY, true);
		}
	}

	private static void drawPanel(GuiGraphics g, Font font, SandBot bot, BotConfig cfg) {
		Minecraft mc = Minecraft.getInstance();
		int accent = Theme.accent();
		BotPhase phase = bot.phase();
		int color = bot.isPaused() ? Theme.WARN : phase.color;

		Theme.card(g, 0, 0, W, H, 0xE00F1115, Theme.alpha(Theme.LINE, 0.9f));
		g.fill(1, 1, 3, H - 1, accent);

		Theme.text(g, font, "SandBot", 8, 5, accent);
		String phaseText = bot.isPaused() ? "PAUSED" : phase.label.toUpperCase();
		int pillW = font.width(phaseText) + 14;
		Theme.card(g, W - pillW - 5, 3, pillW, 12, Theme.alpha(color, 0.15f), Theme.alpha(color, 0.6f));
		Theme.dot(g, W - pillW + 1, 9, Theme.alpha(color, bot.isPaused() ? 1f : 0.5f + 0.5f * Theme.pulse(1400)));
		Theme.text(g, font, phaseText, W - pillW + 6, 5, color);

		Theme.text(g, font, Theme.ellipsize(font, bot.detail(), W - 14), 8, 17, Theme.TEXT_DIM);

		Theme.progress(g, 8, 29, W - 16, 5, bot.cycleProgress(), accent);
		Theme.text(g, font, "Cycle #" + bot.cycleIndex(), 8, 37, Theme.TEXT_FAINT);
		Theme.textRight(g, font, TextUtil.clock(bot.cycleRemainingMs() / 1000) + " left", W - 8, 37, Theme.TEXT);

		int sand = mc.player == null ? 0 : InventoryUtil.countSand(mc.player, cfg.includeRedSand);
		ScanResult scan = bot.lastScan();
		g.pose().pushMatrix();
		g.pose().translate(7, 48);
		g.pose().scale(0.625f, 0.625f);
		g.renderItem(new ItemStack(Items.SAND), 0, 0);
		g.pose().popMatrix();
		Theme.text(g, font, TextUtil.number(sand), 19, 51, Theme.TEXT);
		double held = bot.stats().pendingPayout;
		String money = held > 0 ? TextUtil.money(held) + " held" : TextUtil.money(bot.stats().session.paid) + " paid";
		Theme.textRight(g, font, money, W - 8, 51, held > 0 ? Theme.WARN : Theme.VIOLET);
		String nearby = scan == null ? "-" : TextUtil.number(scan.exposedCount);
		int room = W - 8 - font.width(money) - 6 - 62;
		if (font.width(nearby + " near") <= room) {
			Theme.text(g, font, nearby + " near", 62, 51, Theme.TEXT_DIM);
		} else if (font.width(nearby) <= room) {
			Theme.text(g, font, nearby, 62, 51, Theme.TEXT_DIM);
		}
	}
}
