package com.nunkin.ui.widget;

import com.nunkin.config.ConfigManager;
import com.nunkin.ui.Theme;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class NSlider extends NWidget {
	private static final int VALUE_WIDTH = 46;

	private final double min;
	private final double max;
	private final double step;
	private final DoubleSupplier getter;
	private final DoubleConsumer setter;
	private final DoubleFunction<String> formatter;
	private boolean dragging;

	public NSlider(int x, int y, int width, int height, double min, double max, double step,
				   DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> formatter) {
		super(x, y, width, height, Component.literal("slider"));
		this.min = min;
		this.max = max;
		this.step = step;
		this.getter = getter;
		this.setter = setter;
		this.formatter = formatter;
	}

	private int trackX() {
		return getX() + 4;
	}

	private int trackWidth() {
		return Math.max(10, width - VALUE_WIDTH - 10);
	}

	private void setFromMouse(double mouseX) {
		double pct = Mth.clamp((mouseX - trackX()) / trackWidth(), 0.0, 1.0);
		apply(min + pct * (max - min));
	}

	private void apply(double raw) {
		double snapped = Math.round((raw - min) / step) * step + min;
		snapped = Mth.clamp(snapped, min, max);
		if (snapped != getter.getAsDouble()) {
			setter.accept(snapped);
			ConfigManager.markDirty();
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		dragging = true;
		setFromMouse(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			setFromMouse(event.x());
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		dragging = false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (isFocused()) {
			if (event.isLeft()) {
				apply(getter.getAsDouble() - step);
				return true;
			}
			if (event.isRight()) {
				apply(getter.getAsDouble() + step);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		animate();
		handleCursor(g);
		Font font = Minecraft.getInstance().font;
		int accent = Theme.accent();
		double value = getter.getAsDouble();
		float pct = (float) Mth.clamp((value - min) / (max - min), 0.0, 1.0);
		int tx = trackX();
		int tw = trackWidth();
		int cy = getY() + height / 2;

		Theme.round(g, tx, cy - 2, tw, 4, 0xFF0B0D11);
		int filled = (int) (tw * pct);
		if (filled > 0) {
			Theme.round(g, tx, cy - 2, filled, 4, Theme.shade(accent, 0.9f));
		}
		int knobX = tx + filled - 3;
		int knobColor = dragging ? 0xFFFFFFFF : Theme.mix(0xFFD7DBE2, 0xFFFFFFFF, hover);
		Theme.card(g, knobX, cy - 5, 7, 10, knobColor, Theme.shade(accent, dragging ? 1.2f : 0.9f));

		String text = formatter.apply(value);
		int vx = getX() + width - VALUE_WIDTH;
		Theme.card(g, vx, getY() + (height - 14) / 2, VALUE_WIDTH, 14, Theme.CARD, Theme.LINE);
		Theme.textCenter(g, font, Theme.ellipsize(font, text, VALUE_WIDTH - 4), vx + VALUE_WIDTH / 2, getY() + (height - 8) / 2, Theme.TEXT);
	}
}
