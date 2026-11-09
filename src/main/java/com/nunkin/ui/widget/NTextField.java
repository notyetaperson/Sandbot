package com.nunkin.ui.widget;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.nunkin.ui.Theme;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

/** Vanilla text box (keyboard, clipboard, selection) wrapped in a themed frame. */
public class NTextField extends EditBox {
	private int frameX;
	private int frameY;
	private final int frameW;
	private final int frameH;
	private ScreenRectangle clip;

	public NTextField(Font font, int x, int y, int width, int height, String value, String hint, Consumer<String> onChange) {
		super(font, x + 5, y + (height - 8) / 2, width - 10, 9, Component.literal(hint));
		this.frameX = x;
		this.frameY = y;
		this.frameW = width;
		this.frameH = height;
		setBordered(false);
		setMaxLength(256);
		setTextColor(Theme.TEXT);
		setTextShadow(false);
		setValue(value);
		moveCursorToStart(false);
		if (!hint.isEmpty()) {
			setHint(Component.literal(hint).withColor(Theme.TEXT_FAINT));
		}
		setResponder(onChange);
	}

	public NTextField clip(ScreenRectangle clip) {
		this.clip = clip;
		return this;
	}

	public void setFramePosition(int x, int y) {
		frameX = x;
		frameY = y;
		setX(x + 5);
		setY(y + (frameH - 8) / 2);
	}

	public int frameY() {
		return frameY;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		boolean inFrame = mouseX >= frameX && mouseX < frameX + frameW && mouseY >= frameY && mouseY < frameY + frameH;
		return visible && active && inFrame && (clip == null || clip.containsPoint((int) mouseX, (int) mouseY));
	}

	@Override
	public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (!isVisible()) {
			return;
		}
		boolean hovered = isMouseOver(mouseX, mouseY);
		if (hovered) {
			g.requestCursor(CursorTypes.IBEAM);
		}
		int accent = Theme.accent();
		int border = isFocused() ? accent : hovered ? Theme.alpha(accent, 0.55f) : Theme.LINE;
		Theme.card(g, frameX, frameY, frameW, frameH, isFocused() ? 0xFF161A21 : Theme.CARD, border);
		super.renderWidget(g, mouseX, mouseY, partialTick);
	}
}
