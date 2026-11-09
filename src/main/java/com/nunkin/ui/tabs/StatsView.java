package com.nunkin.ui.tabs;

import com.nunkin.bot.BotStats;
import com.nunkin.bot.SandBot;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.TabView;
import com.nunkin.ui.Theme;
import com.nunkin.ui.widget.NButton;
import com.nunkin.util.TextUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class StatsView implements TabView {
	private static final int CARD_H = 115;

	private BotScreen screen;
	private int x;
	private int y;
	private int w;
	private int h;
	private long sessionArmed = -1;
	private long lifetimeArmed = -1;

	@Override
	public void init(BotScreen screen, int x, int y, int width, int height) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		BotStats stats = SandBot.get().stats();
		int bw = (width - 4) / 2;
		screen.addRenderable(new NButton(x, y + height - 16, bw, 16, "Reset session", NButton.Style.SECONDARY, () -> {
			if (armed(sessionArmed)) {
				stats.resetSession();
				sessionArmed = -1;
			} else {
				sessionArmed = Util.getMillis();
			}
		}).label(() -> armed(sessionArmed) ? "Click to confirm" : "Reset session"));
		screen.addRenderable(new NButton(x + bw + 4, y + height - 16, bw, 16, "Reset all-time", NButton.Style.DANGER, () -> {
			if (armed(lifetimeArmed)) {
				stats.resetLifetime();
				lifetimeArmed = -1;
			} else {
				lifetimeArmed = Util.getMillis();
			}
		}).label(() -> armed(lifetimeArmed) ? "Click to confirm" : "Reset all-time"));
	}

	private static boolean armed(long time) {
		return time > 0 && Util.getMillis() - time < 3000;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = screen.font();
		BotStats stats = SandBot.get().stats();
		int cardW = (w - 4) / 2;
		drawTotals(g, font, x, y, cardW, "THIS SESSION", stats.session, stats.sessionRuntimeSeconds());
		drawTotals(g, font, x + cardW + 4, y, cardW, "ALL TIME", stats.lifetime, stats.lifetimeRuntimeSeconds());
		int chartTop = y + CARD_H + 4;
		int chartH = h - CARD_H - 4 - 20;
		if (chartH >= 40) {
			drawChart(g, font, stats, x, chartTop, w, chartH, mouseX, mouseY);
		}
	}

	private void drawTotals(GuiGraphics g, Font font, int cx, int cy, int cw, String title, BotStats.Totals t, long runtime) {
		Theme.card(g, cx, cy, cw, CARD_H, Theme.CARD, Theme.LINE);
		Theme.textScaled(g, font, title, cx + 6, cy + 5, 0.75f, Theme.accent(), false);
		double hours = Math.max(1 / 60.0, runtime / 3600.0);
		String[][] rows = {
			{"Runtime", TextUtil.durationWords(runtime)},
			{"Cycles", TextUtil.number(t.cycles)},
			{"Sand mined", TextUtil.number(t.sandMined)},
			{"Items sold", TextUtil.number(t.sandSold)},
			{"Earned", TextUtil.money(t.earned)},
			{"Paid out", TextUtil.money(t.paid)},
			{"Teleports", TextUtil.number(t.rtps)},
			{"Raided", TextUtil.number(t.chestsRaided) + " / " + TextUtil.number(t.itemsLooted) + " items"},
			{"Rate", TextUtil.number(Math.round(t.sandMined / hours)) + " sand/h"}
		};
		int[] colors = {Theme.TEXT, Theme.TEXT, Theme.accent(), Theme.TEXT, Theme.GOOD, Theme.VIOLET, Theme.INFO, 0xFFF97316, Theme.TEXT};
		int ry = cy + 16;
		for (int i = 0; i < rows.length; i++) {
			Theme.text(g, font, rows[i][0], cx + 6, ry, Theme.TEXT_DIM);
			Theme.textRight(g, font, rows[i][1], cx + cw - 6, ry, colors[i]);
			ry += 11;
		}
	}

	private void drawChart(GuiGraphics g, Font font, BotStats stats, int cx, int cy, int cw, int ch, int mouseX, int mouseY) {
		Theme.card(g, cx, cy, cw, ch, Theme.CARD, Theme.LINE);
		Theme.textScaled(g, font, "SAND MINED PER CYCLE", cx + 6, cy + 5, 0.75f, Theme.TEXT_FAINT, false);
		Theme.textScaled(g, font, "\u25a0 mined", cx + cw - 80, cy + 5, 0.75f, Theme.accent(), false);
		Theme.textScaled(g, font, "\u25a0 paid", cx + cw - 40, cy + 5, 0.75f, Theme.VIOLET, false);

		List<BotStats.CycleRecord> history = new ArrayList<>(stats.history);
		BotStats.CycleRecord current = stats.current();
		if (current != null) {
			history.add(current);
		}
		int plotX = cx + 6;
		int plotY = cy + 15;
		int plotW = cw - 12;
		int plotH = ch - 21;
		if (history.isEmpty()) {
			Theme.textCenter(g, font, "Finish a cycle to see the chart", cx + cw / 2, plotY + plotH / 2 - 4, Theme.TEXT_FAINT);
			return;
		}
		int maxBars = Math.max(1, plotW / 9);
		List<BotStats.CycleRecord> shown = history.subList(Math.max(0, history.size() - maxBars), history.size());
		int maxMined = 1;
		double maxPaid = 1;
		for (BotStats.CycleRecord r : shown) {
			maxMined = Math.max(maxMined, r.mined);
			maxPaid = Math.max(maxPaid, r.paid);
		}
		for (int i = 1; i <= 3; i++) {
			int gy = plotY + plotH - plotH * i / 4;
			g.fill(plotX, gy, plotX + plotW, gy + 1, 0x10FFFFFF);
		}
		int barW = Math.max(3, Math.min(14, plotW / shown.size() - 3));
		int step = plotW / shown.size();
		BotStats.CycleRecord hovered = null;
		int prevX = -1;
		int prevY = -1;
		for (int i = 0; i < shown.size(); i++) {
			BotStats.CycleRecord r = shown.get(i);
			int bx = plotX + i * step + (step - barW) / 2;
			int bh = (int) (plotH * (r.mined / (double) maxMined));
			boolean live = r == current;
			boolean hover = mouseX >= plotX + i * step && mouseX < plotX + (i + 1) * step && mouseY >= plotY && mouseY < plotY + plotH;
			int color = live ? Theme.alpha(Theme.accent(), 0.55f + 0.3f * Theme.pulse(1200)) : Theme.accent();
			if (hover) {
				hovered = r;
				g.fill(plotX + i * step, plotY, plotX + (i + 1) * step, plotY + plotH, 0x10FFFFFF);
				color = Theme.shade(Theme.accent(), 1.2f);
			}
			if (bh > 0) {
				Theme.round(g, bx, plotY + plotH - bh, barW, bh, color);
			}
			int dotX = bx + barW / 2;
			int dotY = plotY + plotH - (int) (plotH * (r.paid / maxPaid));
			if (r.paid > 0) {
				if (prevX >= 0) {
					drawLine(g, prevX, prevY, dotX, dotY, Theme.alpha(Theme.VIOLET, 0.6f));
				}
				Theme.dot(g, dotX, dotY, Theme.VIOLET);
				prevX = dotX;
				prevY = dotY;
			}
		}
		if (hovered != null) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal("Cycle #" + hovered.index + (hovered == current ? " (running)" : "")).withColor(Theme.accent()));
			lines.add(Component.literal("Mined: " + TextUtil.number(hovered.mined)).withColor(Theme.TEXT));
			lines.add(Component.literal("Sold: " + TextUtil.number(hovered.sold)).withColor(Theme.TEXT));
			lines.add(Component.literal("Earned: " + TextUtil.money(hovered.earned)).withColor(Theme.GOOD));
			lines.add(Component.literal("Paid: " + TextUtil.money(hovered.paid)).withColor(Theme.VIOLET));
			lines.add(Component.literal("Teleports: " + hovered.rtps).withColor(Theme.INFO));
			if (hovered != current) {
				lines.add(Component.literal("Length: " + TextUtil.durationWords(hovered.durationSeconds)).withColor(Theme.TEXT_DIM));
			}
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i++) {
			int px = x0 + (x1 - x0) * i / Math.max(1, steps);
			int py = y0 + (y1 - y0) * i / Math.max(1, steps);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}
}
