package com.nunkin.ui.tabs;

import com.nunkin.bot.SandBot;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.RadarRenderer;
import com.nunkin.ui.TabView;
import com.nunkin.ui.Theme;
import com.nunkin.ui.widget.NButton;
import com.nunkin.util.TextUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class RadarView implements TabView {
	private BotScreen screen;
	private int x;
	private int y;
	private int w;
	private int h;
	private int mapSize;

	@Override
	public void init(BotScreen screen, int x, int y, int width, int height) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		mapSize = Math.max(64, Math.min(height - 4, width - 136));
		int panelX = x + mapSize + 10;
		int panelW = width - mapSize - 10;
		screen.addRenderable(new NButton(panelX, y + h - 20, panelW, 18, "Rescan now", NButton.Style.PRIMARY, () -> {
			if (Minecraft.getInstance().player != null) {
				SandBot.get().rescan();
			}
		}).icon(new ItemStack(Items.SPYGLASS)).tooltip("Scan the area around you right now"));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = screen.font();
		SandBot bot = SandBot.get();
		BotConfig cfg = ConfigManager.get();
		ScanResult scan = bot.lastScan();
		int mapX = x + 2;
		int mapY = y + 2;
		RadarRenderer.render(g, font, scan, mapX, mapY, mapSize - 4, mouseX, mouseY);

		int px = x + mapSize + 10;
		int pw = w - mapSize - 10;
		int cy = y;
		Theme.card(g, px, cy, pw, h - 24, Theme.CARD, Theme.LINE);
		Theme.textScaled(g, font, "SURFACE SCAN", px + 6, cy + 5, 0.75f, Theme.TEXT_FAINT, false);
		cy += 14;

		int exposed = scan == null ? 0 : scan.exposedCount;
		boolean good = exposed > cfg.sandThreshold;
		Theme.text(g, font, scan == null ? "-" : TextUtil.number(exposed), px + 6, cy, good ? Theme.GOOD : Theme.WARN);
		Theme.textScaled(g, font, "exposed sand", px + 8 + font.width(scan == null ? "-" : TextUtil.number(exposed)), cy + 2, 0.75f, Theme.TEXT_DIM, false);
		cy += 11;
		float pct = cfg.sandThreshold <= 0 ? 1f : Math.min(1f, exposed / (float) cfg.sandThreshold);
		Theme.progress(g, px + 6, cy, pw - 12, 5, pct, good ? Theme.GOOD : Theme.WARN);
		cy += 8;
		Theme.textScaled(g, font, "target: more than " + TextUtil.number(cfg.sandThreshold), px + 6, cy, 0.75f, Theme.TEXT_FAINT, false);
		cy += 11;

		if (scan != null) {
			cy = stat(g, font, px, pw, cy, "Chunks loaded", scan.loadedChunks + "/" + scan.totalChunks,
				scan.loadedChunks < scan.totalChunks ? Theme.WARN : Theme.TEXT);
			cy = stat(g, font, px, pw, cy, "Red sand", TextUtil.number(scan.redSandCount) + (cfg.includeRedSand ? "" : " (off)"), Theme.TEXT);
			cy = stat(g, font, px, pw, cy, "Area", scan.size + "x" + scan.size, Theme.TEXT);
			cy = stat(g, font, px, pw, cy, "Scanned", ((Util.getMillis() - scan.time) / 1000) + "s ago", Theme.TEXT);
		}
		int renderDistance = Minecraft.getInstance().options.renderDistance().get();
		if (renderDistance < cfg.scanRadiusChunks) {
			cy = stat(g, font, px, pw, cy, "Render dist.", renderDistance + " (low!)", Theme.BAD);
		}

		cy += 4;
		String[] names = {"Exposed sand", "Red sand", "Water", "Plants", "Other", "Path", "Target", "Drops", "Players", "Containers"};
		int[] colors = {
			RadarRenderer.colorFor(ScanResult.SAND, 1f), RadarRenderer.colorFor(ScanResult.RED_SAND, 1f),
			RadarRenderer.colorFor(ScanResult.WATER, 1f), RadarRenderer.colorFor(ScanResult.FOLIAGE, 1f),
			RadarRenderer.colorFor(ScanResult.OTHER, 1f), 0xFF22D3EE, 0xFFF43F5E, 0xFFFDE047, 0xFFEF4444, RadarRenderer.CONTAINER_COLOR
		};
		int columns = pw >= 150 ? 2 : 1;
		int colW = (pw - 12) / columns;
		int bottomLimit = y + h - 28;
		for (int i = 0; i < names.length; i++) {
			int col = i % columns;
			int row = i / columns;
			int lx = px + 6 + col * colW;
			int ly = cy + row * 9;
			if (ly + 8 > bottomLimit) {
				break;
			}
			g.fill(lx, ly + 1, lx + 6, ly + 7, colors[i]);
			Theme.textScaled(g, font, names[i], lx + 9, ly + 1, 0.75f, Theme.TEXT_DIM, false);
		}
	}

	private static int stat(GuiGraphics g, Font font, int px, int pw, int cy, String label, String value, int color) {
		Theme.textScaled(g, font, label, px + 6, cy + 1, 0.75f, Theme.TEXT_FAINT, false);
		Theme.textRight(g, font, value, px + pw - 6, cy, color);
		return cy + 10;
	}
}
