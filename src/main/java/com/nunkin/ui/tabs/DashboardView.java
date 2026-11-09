package com.nunkin.ui.tabs;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.BotPhase;
import com.nunkin.bot.BotStats;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.TabView;
import com.nunkin.ui.Theme;
import com.nunkin.ui.widget.NButton;
import com.nunkin.util.TextUtil;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class DashboardView implements TabView {
	private static final String[] STEPS = {"RTP", "Scan", "Mine", "Sell", "Pay"};
	private static final int STATUS_H = 50;
	private static final int CARD_H = 30;

	private BotScreen screen;
	private int x;
	private int y;
	private int w;
	private int h;
	private int cardsY;
	private int actionsY;

	@Override
	public void init(BotScreen screen, int x, int y, int width, int height) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		cardsY = y + STATUS_H + 4 + 16 + 4;
		actionsY = cardsY + CARD_H * 2 + 4 + 4;

		SandBot bot = SandBot.get();
		String[] labels = {"Sell now", "RTP now", "Pay now", "End cycle"};
		String[] tips = {
			"Sell all sand right now, then continue",
			"Abandon this spot and search for a new one",
			"Check balance and pay out right now",
			"Finish the cycle now: sell, pay, start the next one"
		};
		Runnable[] actions = {bot::forceSell, bot::forceRtp, bot::forcePay, bot::endCycleNow};
		ItemStack[] icons = {new ItemStack(Items.EMERALD), new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.GOLD_NUGGET), new ItemStack(Items.CLOCK)};
		int gap = 4;
		int bw = (width - gap * 3) / 4;
		boolean showIcons = bw >= 70;
		for (int i = 0; i < 4; i++) {
			NButton button = new NButton(x + i * (bw + gap), actionsY, bw, 18, labels[i], NButton.Style.SECONDARY, actions[i])
				.enabled(bot::isRunning)
				.tooltip(tips[i]);
			if (showIcons) {
				button.icon(icons[i]);
			}
			screen.addRenderable(button);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = screen.font();
		SandBot bot = SandBot.get();
		BotConfig cfg = ConfigManager.get();
		drawStatus(g, font, bot);
		drawPipeline(g, font, bot);
		drawCards(g, font, bot, cfg);
		drawRecent(g, font);
	}

	private void drawStatus(GuiGraphics g, Font font, SandBot bot) {
		int accent = Theme.accent();
		Theme.card(g, x, y, w, STATUS_H, Theme.CARD, Theme.LINE);
		boolean running = bot.isRunning();
		BotPhase phase = bot.phase();
		int color = !running ? Theme.TEXT_FAINT : bot.isPaused() ? Theme.WARN : phase.color;
		String title = !running ? "STOPPED" : bot.isPaused() ? "PAUSED" : phase.label.toUpperCase();

		float glow = running && !bot.isPaused() ? 0.10f + 0.10f * Theme.pulse(1600) : 0.08f;
		Theme.round(g, x + 6, y + 6, 26, 26, Theme.alpha(color, glow));
		Theme.roundOutline(g, x + 6, y + 6, 26, 26, Theme.alpha(color, 0.4f));
		g.renderItem(new ItemStack(running ? phase.icon : Items.BARRIER), x + 11, y + 11);

		Theme.textScaled(g, font, title, x + 39, y + 7, 1.5f, color, false);
		int rightColumn = 92;
		String detail = Theme.ellipsize(font, bot.detail(), w - 45 - rightColumn);
		Theme.text(g, font, detail, x + 39, y + 22, Theme.TEXT_DIM);

		String cycle = running ? "Cycle #" + bot.cycleIndex() : "Not running";
		Theme.textRight(g, font, cycle, x + w - 8, y + 7, Theme.TEXT);
		String time = running ? TextUtil.clock(bot.cycleRemainingMs() / 1000) + " left" : TextUtil.durationWords(ConfigManager.get().cycleSeconds) + " cycles";
		Theme.textRight(g, font, time, x + w - 8, y + 18, running ? accent : Theme.TEXT_FAINT);

		Theme.progress(g, x + 8, y + 36, w - 16, 7, bot.cycleProgress(), running ? accent : Theme.LINE);
		if (running && !bot.isPaused()) {
			int fill = (int) ((w - 18) * bot.cycleProgress());
			if (fill > 6) {
				int shine = x + 9 + (int) ((fill - 6) * Theme.pulse(2200));
				g.fill(shine, y + 37, shine + 6, y + 42, Theme.withAlpha(0xFFFFFF, 50));
			}
		}
	}

	private void drawPipeline(GuiGraphics g, Font font, SandBot bot) {
		int py = y + STATUS_H + 4;
		int step = bot.pipelineStep();
		int gap = 10;
		int chipW = (w - gap * (STEPS.length - 1)) / STEPS.length;
		int accent = Theme.accent();
		for (int i = 0; i < STEPS.length; i++) {
			int cx = x + i * (chipW + gap);
			int fill;
			int border;
			int text;
			if (step < 0) {
				fill = Theme.CARD;
				border = Theme.LINE;
				text = Theme.TEXT_FAINT;
			} else if (i < step) {
				fill = Theme.alpha(Theme.GOOD, 0.12f);
				border = Theme.alpha(Theme.GOOD, 0.45f);
				text = Theme.GOOD;
			} else if (i == step) {
				float p = bot.isPaused() ? 0.5f : Theme.pulse(1200);
				fill = Theme.alpha(accent, 0.15f + 0.12f * p);
				border = accent;
				text = accent;
			} else {
				fill = Theme.CARD;
				border = Theme.LINE;
				text = Theme.TEXT_DIM;
			}
			Theme.card(g, cx, py, chipW, 16, fill, border);
			String label = (i < step && step >= 0 ? "+ " : "") + STEPS[i];
			Theme.textCenter(g, font, label, cx + chipW / 2, py + 4, text);
			if (i < STEPS.length - 1) {
				Theme.textCenter(g, font, ">", cx + chipW + gap / 2 + 1, py + 4, i < step ? Theme.GOOD : Theme.TEXT_FAINT);
			}
		}
	}

	private void drawCards(GuiGraphics g, Font font, SandBot bot, BotConfig cfg) {
		LocalPlayer player = Minecraft.getInstance().player;
		BotStats stats = bot.stats();
		ScanResult scan = bot.lastScan();
		BotStats.CycleRecord cycle = stats.current();

		int inv = player == null ? 0 : InventoryUtil.countSand(player, cfg.includeRedSand);
		int capacity = player == null ? 0 : InventoryUtil.sandCapacity(player, cfg.includeRedSand);
		int exposed = scan == null ? 0 : scan.exposedCount;
		double elapsedMin = Math.max(1.0 / 6.0, bot.cycleElapsedMs() / 60000.0);
		int minedCycle = bot.mineTask().minedThisCycle();

		String[] labels = {"In inventory", "Exposed nearby", "Mined this cycle", "Earned (session)", "Paid out (session)", "Teleports"};
		String[] values = {
			TextUtil.number(inv),
			scan == null ? "-" : TextUtil.number(exposed),
			TextUtil.number(minedCycle),
			TextUtil.money(stats.session.earned),
			TextUtil.money(stats.session.paid),
			String.valueOf(cycle == null ? 0 : cycle.rtps)
		};
		String[] subs = {
			"/ " + TextUtil.number(capacity),
			"need > " + TextUtil.number(cfg.sandThreshold),
			bot.isRunning() ? "~" + Math.round(minedCycle / elapsedMin) + "/min" : "",
			TextUtil.number(stats.session.sandSold) + " sold",
			stats.pendingPayout > 0 ? TextUtil.money(stats.pendingPayout) + " held" : cfg.payTarget.isBlank() ? "no target" : "to " + cfg.payTarget,
			stats.session.chestsRaided > 0 ? stats.session.chestsRaided + " looted" : "session " + stats.session.rtps
		};
		int[] subColors = {
			Theme.TEXT_FAINT, Theme.TEXT_FAINT, Theme.TEXT_FAINT, Theme.TEXT_FAINT,
			stats.pendingPayout > 0 ? Theme.WARN : Theme.TEXT_FAINT, Theme.TEXT_FAINT
		};
		int[] colors = {
			capacity > 0 && inv >= capacity ? Theme.WARN : Theme.TEXT,
			scan == null ? Theme.TEXT_FAINT : exposed > cfg.sandThreshold ? Theme.GOOD : Theme.WARN,
			Theme.accent(),
			Theme.GOOD,
			Theme.VIOLET,
			Theme.INFO
		};
		ItemStack[] icons = {
			new ItemStack(Items.SAND), new ItemStack(Items.SANDSTONE), new ItemStack(Items.IRON_SHOVEL),
			new ItemStack(Items.EMERALD), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.ENDER_PEARL)
		};

		int gap = 4;
		int cardW = (w - gap * 2) / 3;
		for (int i = 0; i < 6; i++) {
			int col = i % 3;
			int row = i / 3;
			int cx = x + col * (cardW + gap);
			int cy = cardsY + row * (CARD_H + gap);
			Theme.card(g, cx, cy, cardW, CARD_H, Theme.CARD, Theme.LINE);
			g.fill(cx + 1, cy + 1, cx + 2, cy + CARD_H - 1, Theme.alpha(colors[i], 0.6f));
			g.renderItem(icons[i], cx + 5, cy + 7);
			Theme.textScaled(g, font, labels[i], cx + 25, cy + 5, 0.75f, Theme.TEXT_FAINT, false);
			Theme.text(g, font, values[i], cx + 25, cy + 15, colors[i]);
			int valueRight = cx + 25 + font.width(values[i]) + 4;
			String sub = subs[i];
			int subWidth = (int) (font.width(sub) * 0.75f);
			if (!sub.isEmpty() && valueRight + subWidth < cx + cardW - 4) {
				Theme.textScaled(g, font, sub, cx + cardW - 4 - subWidth, cy + 17, 0.75f, subColors[i], false);
			}
		}
	}

	private void drawRecent(GuiGraphics g, Font font) {
		int top = actionsY + 18 + 4;
		int available = y + h - top;
		if (available < 16) {
			return;
		}
		Theme.card(g, x, top, w, available, Theme.CARD, Theme.LINE);
		boolean titled = available >= 34;
		if (titled) {
			Theme.textScaled(g, font, "RECENT ACTIVITY", x + 6, top + 4, 0.75f, Theme.TEXT_FAINT, false);
		}
		List<BotLog.Entry> entries = BotLog.snapshot();
		int ly = top + (titled ? 13 : 4);
		int lines = (y + h - ly - 2) / 10;
		int start = Math.max(0, entries.size() - lines);
		for (int i = start; i < entries.size(); i++) {
			BotLog.Entry e = entries.get(i);
			Theme.text(g, font, e.clock(), x + 6, ly, Theme.TEXT_FAINT);
			int mx = x + 6 + font.width("00:00:00") + 6;
			g.fill(mx, ly + 2, mx + 3, ly + 5, e.level().color);
			Theme.text(g, font, Theme.ellipsize(font, e.message(), w - (mx - x) - 14), mx + 7, ly, Theme.TEXT_DIM);
			ly += 10;
		}
	}
}
