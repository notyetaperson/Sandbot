package com.nunkin.ui;

import com.nunkin.bot.SandBot;
import com.nunkin.ui.widget.NButton;
import com.nunkin.util.TextUtil;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Adds a SandBot button (with live status) below the pause menu's buttons. */
public final class PauseMenuIntegration {
	private static final int BUTTON_W = 204;

	private PauseMenuIntegration() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof PauseScreen)) {
				return;
			}
			List<AbstractWidget> buttons = Screens.getButtons(screen);
			int bottom = -1;
			for (AbstractWidget widget : buttons) {
				if (widget.visible && widget.getWidth() >= 200) {
					bottom = Math.max(bottom, widget.getBottom());
				}
			}
			if (bottom < 0) {
				return;
			}
			int x = width / 2 - BUTTON_W / 2;
			int y = bottom + 4;
			int w = BUTTON_W;
			if (y + 20 > height - 2) {
				x = 6;
				y = 6;
				w = 150;
			}
			SandBot bot = SandBot.get();
			NButton button = new NButton(x, y, w, 20, "SandBot Control Panel", NButton.Style.PRIMARY,
				() -> client.setScreen(new BotScreen(screen)))
				.label(() -> {
					if (!bot.isRunning()) {
						return "SandBot Control Panel";
					}
					String state = bot.isPaused() ? "Paused" : bot.phase().label;
					return "SandBot: " + state + " (" + TextUtil.clock(bot.cycleRemainingMs() / 1000) + ")";
				})
				.icon(new ItemStack(Items.SAND))
				.tooltip("Open the SandBot control panel (B)");
			buttons.add(button);
		});
	}
}
