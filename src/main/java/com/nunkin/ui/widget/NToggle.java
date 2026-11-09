package com.nunkin.ui.widget;

import com.nunkin.ui.Theme;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public class NToggle extends NWidget {
	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private float knob = -1f;
	private long lastFrame;

	public NToggle(int x, int y, int width, int height, BooleanSupplier getter, Consumer<Boolean> setter) {
		super(x, y, width, height, Component.literal("toggle"));
		this.getter = getter;
		this.setter = setter;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setter.accept(!getter.getAsBoolean());
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		animate();
		handleCursor(g);
		boolean on = getter.getAsBoolean();
		long now = Util.getMillis();
		float dt = lastFrame == 0 ? 1f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		if (knob < 0) {
			knob = on ? 1f : 0f;
		}
		knob = approach(knob, on ? 1f : 0f, dt * 10f);

		int accent = Theme.accent();
		int trackW = 22;
		int trackH = 12;
		int x = getX();
		int y = getY() + (height - trackH) / 2;
		int track = Theme.mix(0xFF2A2F3A, Theme.shade(accent, 0.8f), knob);
		Theme.card(g, x, y, trackW, trackH, track, Theme.mix(Theme.LINE, accent, Math.max(knob * 0.8f, hover * 0.5f)));
		int knobX = x + 2 + (int) ((trackW - 12) * knob);
		Theme.round(g, knobX, y + 2, 8, trackH - 4, Theme.mix(0xFFB8BEC8, 0xFFFFFFFF, Math.max(knob, hover)));
		g.drawString(Minecraft.getInstance().font, on ? "On" : "Off", x + trackW + 6, getY() + (height - 8) / 2,
			on ? Theme.TEXT : Theme.TEXT_FAINT, false);
	}
}
