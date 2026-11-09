package com.nunkin.ui.widget;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Base for themed widgets: animated hover and optional clipping to a scroll viewport. */
public abstract class NWidget extends AbstractWidget {
	protected ScreenRectangle clip;
	protected float hover;
	private long lastFrame;

	protected NWidget(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	public NWidget clip(ScreenRectangle clip) {
		this.clip = clip;
		return this;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return super.isMouseOver(mouseX, mouseY) && (clip == null || clip.containsPoint((int) mouseX, (int) mouseY));
	}

	protected void animate() {
		long now = Util.getMillis();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		float target = isHovered() && active ? 1f : 0f;
		hover = approach(hover, target, dt * 9f);
	}

	protected static float approach(float current, float target, float step) {
		if (current < target) {
			return Math.min(target, current + step);
		}
		return Math.max(target, current - step);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
