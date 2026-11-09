package com.nunkin.ui;

import com.nunkin.config.ConfigManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

public final class Theme {
	public static final int PANEL = 0xF20F1115;
	public static final int PANEL_EDGE = 0xFF2A2F3A;
	public static final int HEADER = 0xFF14171D;
	public static final int SIDEBAR = 0xFF12151A;
	public static final int CARD = 0xFF1A1E26;
	public static final int CARD_2 = 0xFF222733;
	public static final int CARD_HOVER = 0xFF2A303D;
	public static final int LINE = 0xFF2B303B;
	public static final int TEXT = 0xFFE6E8EC;
	public static final int TEXT_DIM = 0xFF9BA3AF;
	public static final int TEXT_FAINT = 0xFF5D6470;
	public static final int GOOD = 0xFF34D399;
	public static final int WARN = 0xFFFBBF24;
	public static final int BAD = 0xFFF87171;
	public static final int INFO = 0xFF60A5FA;
	public static final int VIOLET = 0xFFA78BFA;

	public static final String[] ACCENT_NAMES = {"Sand", "Emerald", "Sky", "Violet", "Rose", "Amber"};
	private static final int[] ACCENTS = {0xFFE8C872, 0xFF34D399, 0xFF38BDF8, 0xFFA78BFA, 0xFFFB7185, 0xFFF59E0B};

	private Theme() {
	}

	public static int accent() {
		return ACCENTS[Math.floorMod(ConfigManager.get().accent, ACCENTS.length)];
	}

	public static int accentCount() {
		return ACCENTS.length;
	}

	public static int alpha(int color, float factor) {
		int a = (int) (((color >>> 24) & 0xFF) * Mth.clamp(factor, 0f, 1f));
		return (a << 24) | (color & 0xFFFFFF);
	}

	public static int withAlpha(int color, int alpha) {
		return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
	}

	public static int mix(int a, int b, float t) {
		t = Mth.clamp(t, 0f, 1f);
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int oa = (int) (aa + (ba - aa) * t);
		int or = (int) (ar + (br - ar) * t);
		int og = (int) (ag + (bg - ag) * t);
		int ob = (int) (ab + (bb - ab) * t);
		return (oa << 24) | (or << 16) | (og << 8) | ob;
	}

	public static int shade(int color, float factor) {
		int r = Mth.clamp((int) (((color >> 16) & 0xFF) * factor), 0, 255);
		int g = Mth.clamp((int) (((color >> 8) & 0xFF) * factor), 0, 255);
		int b = Mth.clamp((int) ((color & 0xFF) * factor), 0, 255);
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	/** Readable text color for the given background. */
	public static int onColor(int background) {
		int r = (background >> 16) & 0xFF, g = (background >> 8) & 0xFF, b = background & 0xFF;
		return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? 0xFF111317 : 0xFFFFFFFF;
	}

	public static float pulse(long periodMs) {
		return (float) (0.5 + 0.5 * Math.sin((Util.getMillis() % periodMs) / (double) periodMs * Math.PI * 2));
	}

	public static void box(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	/** Rectangle with 1px clipped corners. */
	public static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
		if (w <= 2 || h <= 2) {
			box(g, x, y, w, h, color);
			return;
		}
		g.fill(x + 1, y, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	public static void roundOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x + 1, y, x + w - 1, y + 1, color);
		g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	public static void card(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
		box(g, x + 1, y + 1, w - 2, h - 2, fill);
		roundOutline(g, x, y, w, h, border);
	}

	public static void shadow(GuiGraphics g, int x, int y, int w, int h, float strength) {
		for (int i = 1; i <= 6; i++) {
			roundOutline(g, x - i, y - i + 2, w + i * 2, h + i * 2, withAlpha(0, (int) (strength * 70 / i)));
		}
	}

	public static void progress(GuiGraphics g, int x, int y, int w, int h, float pct, int color) {
		round(g, x, y, w, h, 0xFF0B0D11);
		int fill = (int) ((w - 2) * Mth.clamp(pct, 0f, 1f));
		if (fill > 0) {
			round(g, x + 1, y + 1, fill, h - 2, color);
			if (h > 4) {
				g.fill(x + 2, y + 1, x + fill, y + 2, withAlpha(0xFFFFFF, 60));
			}
		}
	}

	public static void dot(GuiGraphics g, int cx, int cy, int color) {
		g.fill(cx - 1, cy - 2, cx + 2, cy + 3, color);
		g.fill(cx - 2, cy - 1, cx + 3, cy + 2, color);
	}

	public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
		g.drawString(font, s, x, y, color, false);
	}

	public static void textRight(GuiGraphics g, Font font, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, false);
	}

	public static void textCenter(GuiGraphics g, Font font, String s, int cx, int y, int color) {
		g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
	}

	public static void textScaled(GuiGraphics g, Font font, String s, float x, float y, float scale, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.drawString(font, s, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	public static String ellipsize(Font font, String s, int maxWidth) {
		if (font.width(s) <= maxWidth) {
			return s;
		}
		String dots = "...";
		return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width(dots))) + dots;
	}
}
