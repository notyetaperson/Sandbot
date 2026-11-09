package com.nunkin.bot;

import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.Theme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

public final class Notifications {
	private record Toast(String title, String body, int color, long start) {
	}

	private static final long DURATION_MS = 5000;
	private static final long SLIDE_MS = 220;
	private static final List<Toast> TOASTS = new ArrayList<>();

	private Notifications() {
	}

	public static void push(String title, String body, int color) {
		BotConfig cfg = ConfigManager.get();
		if (cfg.toasts) {
			TOASTS.add(new Toast(title, body, color, Util.getMillis()));
			while (TOASTS.size() > 4) {
				TOASTS.remove(0);
			}
		}
		if (cfg.chatFeedback) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				Component line = Component.empty()
					.append(Component.literal("[SandBot] ").withColor(color))
					.append(Component.literal(title + ": ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(body).withStyle(ChatFormatting.GRAY));
				ChatWatcher.withoutCapture(() -> mc.player.displayClientMessage(line, false));
			}
		}
	}

	/** Draws stacked toasts growing downward from (edgeX, y). */
	public static void render(GuiGraphics g, Font font, int edgeX, int y, boolean rightAligned) {
		long now = Util.getMillis();
		TOASTS.removeIf(t -> now - t.start() > DURATION_MS);
		int cursorY = y;
		for (Toast t : TOASTS) {
			long age = now - t.start();
			float in = Mth.clamp(age / (float) SLIDE_MS, 0f, 1f);
			float out = Mth.clamp((DURATION_MS - age) / 400f, 0f, 1f);
			float ease = 1f - (1f - in) * (1f - in) * (1f - in);
			float alpha = Math.min(in, out);
			String body = Theme.ellipsize(font, t.body(), 220);
			int w = Math.max(font.width(t.title()), font.width(body)) + 18;
			int h = 26;
			int slide = (int) ((1f - ease) * (w + 8));
			int x = rightAligned ? edgeX - w + slide : edgeX - slide;
			Theme.card(g, x, cursorY, w, h, Theme.alpha(0xF0101217, alpha), Theme.alpha(Theme.LINE, alpha));
			g.fill(x + 1, cursorY + 1, x + 3, cursorY + h - 1, Theme.alpha(t.color(), alpha));
			float life = 1f - age / (float) DURATION_MS;
			g.fill(x + 3, cursorY + h - 2, x + 3 + (int) ((w - 4) * life), cursorY + h - 1, Theme.alpha(t.color(), alpha * 0.6f));
			g.drawString(font, t.title(), x + 9, cursorY + 4, Theme.alpha(t.color(), alpha), false);
			g.drawString(font, body, x + 9, cursorY + 14, Theme.alpha(Theme.TEXT_DIM, alpha), false);
			cursorY += h + 4;
		}
	}
}
