package com.nunkin.ui.widget;

import com.nunkin.ui.Theme;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class NButton extends NWidget {
	public enum Style {
		PRIMARY, SECONDARY, DANGER, SUCCESS, GHOST
	}

	private final Runnable onPress;
	private Supplier<String> label;
	private Supplier<Style> style;
	private Supplier<Boolean> enabled = () -> true;
	private ItemStack icon = ItemStack.EMPTY;

	public NButton(int x, int y, int width, int height, String label, Style style, Runnable onPress) {
		super(x, y, width, height, Component.literal(label));
		this.label = () -> label;
		this.style = () -> style;
		this.onPress = onPress;
	}

	public NButton label(Supplier<String> label) {
		this.label = label;
		return this;
	}

	public NButton style(Supplier<Style> style) {
		this.style = style;
		return this;
	}

	public NButton enabled(Supplier<Boolean> enabled) {
		this.enabled = enabled;
		return this;
	}

	public NButton icon(ItemStack icon) {
		this.icon = icon;
		return this;
	}

	public NButton tooltip(String text) {
		setTooltip(Tooltip.create(Component.literal(text)));
		return this;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onPress.run();
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		active = enabled.get();
		animate();
		handleCursor(g);
		Font font = Minecraft.getInstance().font;
		int accent = Theme.accent();
		int fill;
		int border;
		int text;
		switch (style.get()) {
			case PRIMARY -> {
				fill = Theme.mix(accent, Theme.shade(accent, 1.15f), hover);
				border = Theme.shade(accent, 1.25f);
				text = Theme.onColor(accent);
			}
			case DANGER -> {
				fill = Theme.mix(0xFF7F1D1D, 0xFF9B2424, hover);
				border = 0xFFB33A3A;
				text = 0xFFFFE4E4;
			}
			case SUCCESS -> {
				fill = Theme.mix(0xFF0F5C45, 0xFF137356, hover);
				border = 0xFF1F8F6B;
				text = 0xFFE3FFF4;
			}
			case GHOST -> {
				fill = Theme.alpha(Theme.CARD_HOVER, hover);
				border = Theme.alpha(Theme.LINE, hover);
				text = Theme.mix(Theme.TEXT_DIM, Theme.TEXT, hover);
			}
			default -> {
				fill = Theme.mix(Theme.CARD_2, Theme.CARD_HOVER, hover);
				border = Theme.mix(Theme.LINE, Theme.alpha(accent, 0.6f), hover);
				text = Theme.TEXT;
			}
		}
		if (!active) {
			fill = Theme.CARD;
			border = Theme.LINE;
			text = Theme.TEXT_FAINT;
		}
		int x = getX();
		int y = getY();
		Theme.card(g, x, y, width, height, fill, border);
		if (isFocused() && active && Minecraft.getInstance().getLastInputType().isKeyboard()) {
			Theme.roundOutline(g, x - 1, y - 1, width + 2, height + 2, Theme.alpha(accent, 0.5f));
		}

		String s = label.get();
		int iconWidth = icon.isEmpty() ? 0 : 18;
		String shown = Theme.ellipsize(font, s, width - 6 - iconWidth);
		int contentWidth = font.width(shown) + iconWidth;
		int cx = x + (width - contentWidth) / 2;
		if (!icon.isEmpty()) {
			g.renderItem(icon, cx, y + (height - 16) / 2);
			cx += iconWidth;
		}
		g.drawString(font, shown, cx, y + (height - 8) / 2, text, false);
	}
}
