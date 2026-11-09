package com.nunkin.ui.widget;

import com.nunkin.ui.Theme;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Sidebar navigation entry with an item icon, label, hotkey hint and accent marker. */
public class NTab extends NWidget {
	private final String label;
	private final String hotkey;
	private final ItemStack icon;
	private final BooleanSupplier selected;
	private final Runnable onSelect;
	private float selectAnim;

	public NTab(int x, int y, int width, int height, String label, String hotkey, ItemStack icon, BooleanSupplier selected, Runnable onSelect) {
		super(x, y, width, height, Component.literal(label));
		this.label = label;
		this.hotkey = hotkey;
		this.icon = icon;
		this.selected = selected;
		this.onSelect = onSelect;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		onSelect.run();
	}

	@Override
	protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		animate();
		handleCursor(g);
		boolean sel = selected.getAsBoolean();
		selectAnim = approach(selectAnim, sel ? 1f : 0f, 0.2f);
		Font font = Minecraft.getInstance().font;
		int accent = Theme.accent();
		int x = getX();
		int y = getY();
		int bg = Theme.mix(Theme.alpha(Theme.CARD_HOVER, hover * 0.8f), Theme.CARD_2, selectAnim);
		Theme.round(g, x, y, width, height, bg);
		if (selectAnim > 0.01f) {
			int barH = (int) ((height - 6) * selectAnim);
			g.fill(x, y + (height - barH) / 2, x + 2, y + (height + barH) / 2, accent);
		}
		g.renderItem(icon, x + 6, y + (height - 16) / 2);
		int textColor = Theme.mix(Theme.mix(Theme.TEXT_DIM, Theme.TEXT, hover), accent, selectAnim);
		g.drawString(font, label, x + 26, y + (height - 8) / 2, textColor, false);
		if (width > 90) {
			Theme.textRight(g, font, hotkey, x + width - 5, y + (height - 8) / 2, Theme.alpha(Theme.TEXT_FAINT, 0.8f));
		}
	}
}
