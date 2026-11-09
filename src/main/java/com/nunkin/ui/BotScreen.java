package com.nunkin.ui;

import com.nunkin.NunkinClient;
import com.nunkin.bot.BotPhase;
import com.nunkin.bot.Notifications;
import com.nunkin.bot.SandBot;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.tabs.DashboardView;
import com.nunkin.ui.tabs.LogView;
import com.nunkin.ui.tabs.RadarView;
import com.nunkin.ui.tabs.SettingsView;
import com.nunkin.ui.tabs.StatsView;
import com.nunkin.ui.widget.NButton;
import com.nunkin.ui.widget.NTab;
import com.nunkin.util.TextUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** The SandBot control panel (B key or the pause menu button). */
public class BotScreen extends Screen {
	public enum Tab {
		DASHBOARD("Dashboard", Items.SAND),
		RADAR("Radar", Items.COMPASS),
		SETTINGS("Settings", Items.COMPARATOR),
		LOG("Log", Items.WRITABLE_BOOK),
		STATS("Stats", Items.GOLD_INGOT);

		final String label;
		final Item icon;

		Tab(String label, Item icon) {
			this.label = label;
			this.icon = icon;
		}
	}

	private static final int HEADER_H = 24;
	private static Tab currentTab = Tab.DASHBOARD;

	private final @Nullable Screen parent;
	private final long openedAt = Util.getMillis();
	private int px;
	private int py;
	private int pw;
	private int ph;
	private int sideW;
	private TabView view;

	public BotScreen(@Nullable Screen parent) {
		super(Component.literal("Nunkin SandBot"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		pw = Math.min(width - 12, 580);
		ph = Math.min(height - 12, 340);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		sideW = pw < 460 ? 88 : 108;
		int cx = px + sideW + 8;
		int cy = py + HEADER_H + 7;
		int cw = pw - sideW - 15;
		int ch = ph - HEADER_H - 14;

		addRenderableWidget(new NButton(px + pw - 20, py + 5, 15, 14, "x", NButton.Style.GHOST, this::onClose).tooltip("Close (Esc / B)"));

		int ty = py + HEADER_H + 7;
		int number = 1;
		for (Tab tab : Tab.values()) {
			addRenderableWidget(new NTab(px + 5, ty, sideW - 10, 20, tab.label, String.valueOf(number++), new ItemStack(tab.icon),
				() -> currentTab == tab, () -> switchTab(tab)));
			ty += 22;
		}

		SandBot bot = SandBot.get();
		int bottom = py + ph - 6;
		addRenderableWidget(new NButton(px + 5, bottom - 20, sideW - 10, 20, "Start", NButton.Style.SUCCESS, bot::toggle)
			.label(() -> bot.isRunning() ? "Stop bot" : "Start bot")
			.style(() -> bot.isRunning() ? NButton.Style.DANGER : NButton.Style.SUCCESS));
		addRenderableWidget(new NButton(px + 5, bottom - 40, sideW - 10, 17, "Pause", NButton.Style.SECONDARY,
			() -> bot.setPaused(!bot.isPaused(), "Paused from the panel"))
			.label(() -> bot.isPaused() ? "Resume" : "Pause")
			.enabled(bot::isRunning));

		view = switch (currentTab) {
			case DASHBOARD -> new DashboardView();
			case RADAR -> new RadarView();
			case SETTINGS -> new SettingsView();
			case LOG -> new LogView();
			case STATS -> new StatsView();
		};
		view.init(this, cx, cy, cw, ch);
	}

	public void switchTab(Tab tab) {
		if (tab != currentTab) {
			currentTab = tab;
			ConfigManager.saveIfDirty();
			rebuildWidgets();
		}
	}

	public void rebuild() {
		rebuildWidgets();
	}

	public <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderable(T widget) {
		return addRenderableWidget(widget);
	}

	public <T extends GuiEventListener & NarratableEntry> T addInput(T widget) {
		return addWidget(widget);
	}

	public Font font() {
		return font;
	}

	private float openProgress() {
		return Mth.clamp((Util.getMillis() - openedAt) / 180f, 0f, 1f);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		float t = openProgress();
		g.fillGradient(0, 0, width, height, Theme.alpha(0xB0080A0E, t), Theme.alpha(0xD8020304, t));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		float open = openProgress();
		float ease = 1f - (1f - open) * (1f - open) * (1f - open);
		g.pose().pushMatrix();
		g.pose().translate(0, (1f - ease) * 12f);

		int accent = Theme.accent();
		Theme.shadow(g, px, py, pw, ph, ease);
		Theme.card(g, px, py, pw, ph, Theme.PANEL, Theme.PANEL_EDGE);

		Theme.round(g, px + 1, py + 1, pw - 2, HEADER_H - 1, Theme.HEADER);
		g.fill(px + 2, py + 1, px + pw - 2, py + 2, accent);
		g.fill(px + 1, py + HEADER_H, px + pw - 1, py + HEADER_H + 1, Theme.LINE);
		g.renderItem(new ItemStack(Items.SAND), px + 6, py + 4);
		Theme.text(g, font, "NUNKIN", px + 26, py + 9, accent);
		Theme.text(g, font, "SandBot", px + 26 + font.width("NUNKIN") + 4, py + 9, Theme.TEXT);
		drawStatusPill(g, px + pw - 26, py + 5);
		drawConnectionInfo(g);

		g.fill(px + 1, py + HEADER_H + 1, px + sideW, py + ph - 1, Theme.SIDEBAR);
		g.fill(px + sideW, py + HEADER_H + 1, px + sideW + 1, py + ph - 1, Theme.LINE);
		drawSidebarTimer(g);

		view.render(g, mouseX, mouseY, partialTick);
		super.render(g, mouseX, mouseY, partialTick);
		view.renderAfter(g, mouseX, mouseY, partialTick);
		g.pose().popMatrix();

		boolean roomBeside = width - (px + pw) >= 160;
		Notifications.render(g, font, roomBeside ? width - 6 : px + pw - 4, roomBeside ? 6 : py + HEADER_H + 4, true);
	}

	private void drawStatusPill(GuiGraphics g, int right, int y) {
		SandBot bot = SandBot.get();
		String label;
		int color;
		if (!bot.isRunning()) {
			label = "IDLE";
			color = Theme.TEXT_FAINT;
		} else if (bot.isPaused()) {
			label = "PAUSED";
			color = Theme.WARN;
		} else {
			BotPhase phase = bot.phase();
			label = phase.label.toUpperCase();
			color = phase.color;
		}
		int w = font.width(label) + 18;
		int x = right - w;
		Theme.card(g, x, y, w, 14, Theme.alpha(color, 0.14f), Theme.alpha(color, 0.55f));
		float glow = bot.isRunning() && !bot.isPaused() ? 0.45f + 0.55f * Theme.pulse(1400) : 1f;
		Theme.dot(g, x + 6, y + 7, Theme.alpha(color, glow));
		Theme.text(g, font, label, x + 12, y + 3, color);
	}

	private void drawConnectionInfo(GuiGraphics g) {
		if (minecraft == null || minecraft.player == null) {
			return;
		}
		ServerData server = minecraft.getCurrentServer();
		String where = server != null ? server.ip : minecraft.isSingleplayer() ? "Singleplayer" : "";
		String info = minecraft.player.getGameProfile().name() + (where.isEmpty() ? "" : " @ " + where);
		int left = px + 26 + font.width("NUNKIN SandBot") + 14;
		int right = px + pw - 120;
		if (right - left > 40) {
			Theme.textCenter(g, font, Theme.ellipsize(font, info, right - left), (left + right) / 2, py + 9, Theme.TEXT_FAINT);
		}
	}

	private void drawSidebarTimer(GuiGraphics g) {
		SandBot bot = SandBot.get();
		int x = px + 6;
		int w = sideW - 12;
		int y = py + ph - 6 - 40 - 26;
		int tabsBottom = py + HEADER_H + 7 + Tab.values().length * 22;
		if (y < tabsBottom + 2) {
			return;
		}
		String left = bot.isRunning() ? "Cycle #" + bot.cycleIndex() : "Cycle";
		String right = bot.isRunning() ? TextUtil.clock(bot.cycleRemainingMs() / 1000) : "--:--";
		Theme.text(g, font, left, x, y, Theme.TEXT_DIM);
		Theme.textRight(g, font, right, x + w, y, bot.isRunning() ? Theme.accent() : Theme.TEXT_FAINT);
		Theme.progress(g, x, y + 11, w, 5, bot.cycleProgress(), Theme.accent());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (view != null && view.mouseScrolled(mouseX, mouseY, scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox box && box.isFocused();
		if (!typing) {
			if (NunkinClient.openKey().matches(event)) {
				onClose();
				return true;
			}
			int digit = event.getDigit();
			if (digit >= 1 && digit <= Tab.values().length) {
				switchTab(Tab.values()[digit - 1]);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		if (view != null) {
			view.tick();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}
}
