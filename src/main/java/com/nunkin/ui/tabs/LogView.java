package com.nunkin.ui.tabs;

import com.nunkin.bot.BotLog;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.TabView;
import com.nunkin.ui.Theme;
import com.nunkin.ui.widget.NButton;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class LogView implements TabView {
	private static final int LINE_H = 11;
	private static BotLog.Level filter;
	private static int offset;
	private static boolean follow = true;

	private BotScreen screen;
	private int x;
	private int y;
	private int w;
	private int h;
	private int listTop;
	private int maxOffset;

	@Override
	public void init(BotScreen screen, int x, int y, int width, int height) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		this.listTop = y + 22;

		BotLog.Level[] levels = {null, BotLog.Level.INFO, BotLog.Level.SUCCESS, BotLog.Level.WARN, BotLog.Level.ERROR, BotLog.Level.SERVER};
		String[] names = {"All", "Info", "OK", "Warn", "Error", "Server"};
		Font font = screen.font();
		int chipsWidth = 0;
		for (String name : names) {
			chipsWidth += font.width(name) + 15;
		}
		if (chipsWidth + 104 > width) {
			names = new String[]{"All", "Inf", "OK", "Wrn", "Err", "Srv"};
		}
		int bx = x;
		for (int i = 0; i < levels.length; i++) {
			BotLog.Level level = levels[i];
			int bw = font.width(names[i]) + 12;
			screen.addRenderable(new NButton(bx, y, bw, 16, names[i], NButton.Style.GHOST, () -> {
				filter = level;
				follow = true;
			}).style(() -> filter == level ? NButton.Style.PRIMARY : NButton.Style.GHOST));
			bx += bw + 3;
		}
		screen.addRenderable(new NButton(x + width - 46, y, 46, 16, "Clear", NButton.Style.SECONDARY, () -> {
			BotLog.clear();
			offset = 0;
			follow = true;
		}).tooltip("Clear the log"));
		screen.addRenderable(new NButton(x + width - 96, y, 46, 16, "Copy", NButton.Style.SECONDARY,
			() -> Minecraft.getInstance().keyboardHandler.setClipboard(BotLog.export())).tooltip("Copy the whole log to the clipboard"));
	}

	private List<BotLog.Entry> entries() {
		List<BotLog.Entry> out = new ArrayList<>();
		for (BotLog.Entry e : BotLog.snapshot()) {
			if (filter == null || e.level() == filter) {
				out.add(e);
			}
		}
		return out;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = screen.font();
		int listH = y + h - listTop;
		Theme.card(g, x, listTop, w, listH, Theme.CARD, Theme.LINE);
		List<BotLog.Entry> list = entries();
		int visible = Math.max(1, (listH - 6) / LINE_H);
		maxOffset = Math.max(0, list.size() - visible);
		if (follow) {
			offset = maxOffset;
		}
		offset = Mth.clamp(offset, 0, maxOffset);

		if (list.isEmpty()) {
			Theme.textCenter(g, font, "Nothing logged yet", x + w / 2, listTop + listH / 2 - 4, Theme.TEXT_FAINT);
			return;
		}

		int timeW = font.width("00:00:00");
		int tagW = font.width("WRN") + 6;
		int msgX = x + 6 + timeW + 6 + tagW;
		int msgW = w - (msgX - x) - 10;
		BotLog.Entry hovered = null;
		g.enableScissor(x + 1, listTop + 1, x + w - 1, listTop + listH - 1);
		for (int i = 0; i < visible && offset + i < list.size(); i++) {
			BotLog.Entry e = list.get(offset + i);
			int ly = listTop + 4 + i * LINE_H;
			if (mouseX >= x && mouseX < x + w && mouseY >= ly - 1 && mouseY < ly + LINE_H - 1) {
				g.fill(x + 2, ly - 1, x + w - 6, ly + LINE_H - 1, Theme.alpha(Theme.CARD_HOVER, 0.7f));
				hovered = e;
			} else if (i % 2 == 0) {
				g.fill(x + 2, ly - 1, x + w - 6, ly + LINE_H - 1, 0x08FFFFFF);
			}
			Theme.text(g, font, e.clock(), x + 6, ly, Theme.TEXT_FAINT);
			Theme.text(g, font, e.level().tag, x + 6 + timeW + 6, ly, e.level().color);
			int color = e.level() == BotLog.Level.INFO || e.level() == BotLog.Level.DEBUG ? Theme.TEXT_DIM : Theme.TEXT;
			Theme.text(g, font, Theme.ellipsize(font, e.message(), msgW), msgX, ly, color);
		}
		g.disableScissor();

		if (maxOffset > 0) {
			int trackX = x + w - 5;
			int trackTop = listTop + 3;
			int trackH = listH - 6;
			int thumbH = Math.max(14, trackH * visible / list.size());
			int thumbY = trackTop + (int) ((trackH - thumbH) * (offset / (float) maxOffset));
			g.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x22FFFFFF);
			g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, Theme.alpha(Theme.accent(), 0.8f));
		}

		if (hovered != null && font.width(hovered.message()) > msgW) {
			List<Component> lines = new ArrayList<>();
			for (var seq : font.split(Component.literal(hovered.message()), 260)) {
				StringBuilder sb = new StringBuilder();
				seq.accept((index, style, cp) -> {
					sb.appendCodePoint(cp);
					return true;
				});
				lines.add(Component.literal(sb.toString()));
			}
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < x || mouseX > x + w || mouseY < listTop || mouseY > y + h) {
			return false;
		}
		offset = Mth.clamp(offset - (int) Math.signum(amount) * 3, 0, maxOffset);
		follow = offset >= maxOffset;
		return true;
	}
}
