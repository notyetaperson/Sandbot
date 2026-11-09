package com.nunkin.ui;

import net.minecraft.client.gui.GuiGraphics;

public interface TabView {
	void init(BotScreen screen, int x, int y, int width, int height);

	/** Drawn before the screen's regular widgets. */
	void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);

	/** Drawn after the screen's regular widgets. */
	default void renderAfter(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
	}

	default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	default void tick() {
	}
}
