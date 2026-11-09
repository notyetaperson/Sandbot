package com.nunkin.ui.widget;

import com.nunkin.config.ConfigManager;
import com.nunkin.ui.Theme;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Click to go to the next value, right-click for the previous one. */
public class NCycle<T> extends NWidget {
	private final List<T> values;
	private final Supplier<T> getter;
	private final Consumer<T> setter;
	private final Function<T, String> labeler;

	public NCycle(int x, int y, int width, int height, List<T> values, Supplier<T> getter, Consumer<T> setter, Function<T, String> labeler) {
		super(x, y, width, height, Component.literal("cycle"));
		this.values = values;
		this.getter = getter;
		this.setter = setter;
		this.labeler = labeler;
	}

	@Override
	protected boolean isValidClickButton(MouseButtonInfo info) {
		return info.button() == 0 || info.button() == 1;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		int index = Math.max(0, values.indexOf(getter.get()));
		int dir = event.button() == 1 ? -1 : 1;
		setter.accept(values.get(Math.floorMod(index + dir, values.size())));
		ConfigManager.markDirty();
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		animate();
		handleCursor(g);
		Font font = Minecraft.getInstance().font;
		int accent = Theme.accent();
		int x = getX();
		int y = getY();
		Theme.card(g, x, y, width, height, Theme.mix(Theme.CARD_2, Theme.CARD_HOVER, hover), Theme.mix(Theme.LINE, Theme.alpha(accent, 0.7f), hover));
		int arrowColor = Theme.mix(Theme.TEXT_FAINT, accent, hover);
		g.drawString(font, "<", x + 5, y + (height - 8) / 2, arrowColor, false);
		g.drawString(font, ">", x + width - 10, y + (height - 8) / 2, arrowColor, false);
		String label = Theme.ellipsize(font, labeler.apply(getter.get()), width - 26);
		Theme.textCenter(g, font, label, x + width / 2, y + (height - 8) / 2, Theme.TEXT);

		int count = values.size();
		if (count > 1 && count <= 8) {
			int index = Math.max(0, values.indexOf(getter.get()));
			int total = count * 4 - 2;
			int dx = x + (width - total) / 2;
			for (int i = 0; i < count; i++) {
				g.fill(dx + i * 4, y + height - 3, dx + i * 4 + 2, y + height - 2, i == index ? accent : Theme.LINE);
			}
		}
	}
}
