package com.nunkin.ui.tabs;

import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.TabView;
import com.nunkin.ui.Theme;
import com.nunkin.ui.widget.NButton;
import com.nunkin.ui.widget.NCycle;
import com.nunkin.ui.widget.NSlider;
import com.nunkin.ui.widget.NTextField;
import com.nunkin.ui.widget.NToggle;
import com.nunkin.ui.widget.NWidget;
import com.nunkin.util.TextUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

public final class SettingsView implements TabView {
	private static final int ROW_H = 22;
	private static final int HEADER_H = 18;
	private static double scroll;
	private static String filter = "";

	private interface Factory {
		AbstractWidget create(int x, int y, int width, int height, ScreenRectangle clip);
	}

	private record Row(String section, String label, String help, Factory factory) {
		boolean isHeader() {
			return factory == null;
		}

		boolean matches(String query) {
			if (query.isEmpty()) return true;
			return label.toLowerCase(Locale.ROOT).contains(query) || section.toLowerCase(Locale.ROOT).contains(query)
				|| help.toLowerCase(Locale.ROOT).contains(query);
		}
	}

	private static final class Placed {
		final Row row;
		final AbstractWidget widget;
		int y;
		boolean shown;

		Placed(Row row, AbstractWidget widget) {
			this.row = row;
			this.widget = widget;
		}
	}

	private final List<Placed> placed = new ArrayList<>();
	private BotScreen screen;
	private int x;
	private int y;
	private int w;
	private int h;
	private ScreenRectangle viewport;
	private int labelW;
	private int contentHeight;
	private long resetArmedAt = -1;
	private String currentSection = "";

	private static BotConfig c() {
		return ConfigManager.get();
	}

	@Override
	public void init(BotScreen screen, int x, int y, int width, int height) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		Font font = screen.font();

		NTextField search = new NTextField(font, x, y, width - 96, 16, filter, "Search settings...", value -> {
			filter = value;
			scroll = 0;
			layout();
		});
		screen.addRenderable(search);
		screen.addRenderable(new NButton(x + width - 92, y, 92, 16, "Reset defaults", NButton.Style.DANGER, this::resetDefaults)
			.label(() -> isResetArmed() ? "Click to confirm" : "Reset defaults")
			.tooltip("Restore every setting to its default value"));

		viewport = new ScreenRectangle(x, y + 22, width, height - 22);
		labelW = Math.max(90, (int) (width * 0.42));
		int controlX = x + labelW;
		int controlW = width - labelW - 10;

		for (Row row : buildRows()) {
			AbstractWidget widget = null;
			if (!row.isHeader()) {
				widget = row.factory().create(controlX, 0, controlW, 16, viewport);
				screen.addInput(widget);
			}
			placed.add(new Placed(row, widget));
		}
		layout();
	}

	private boolean isResetArmed() {
		return resetArmedAt > 0 && Util.getMillis() - resetArmedAt < 3000;
	}

	private void resetDefaults() {
		if (isResetArmed()) {
			resetArmedAt = -1;
			ConfigManager.resetToDefaults();
			screen.rebuild();
		} else {
			resetArmedAt = Util.getMillis();
		}
	}

	private void layout() {
		String query = filter.trim().toLowerCase(Locale.ROOT);
		int cursor = 0;
		for (int i = 0; i < placed.size(); i++) {
			Placed p = placed.get(i);
			boolean show;
			if (p.row.isHeader()) {
				show = false;
				for (int j = i + 1; j < placed.size() && !placed.get(j).row.isHeader(); j++) {
					if (placed.get(j).row.matches(query)) {
						show = true;
						break;
					}
				}
			} else {
				show = p.row.matches(query);
			}
			p.shown = show;
			if (show) {
				p.y = cursor;
				cursor += p.row.isHeader() ? HEADER_H : ROW_H;
			}
		}
		contentHeight = cursor + 4;
		scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - viewport.height()));
		positionWidgets();
	}

	private void positionWidgets() {
		int top = viewport.top() - (int) scroll;
		for (Placed p : placed) {
			if (p.widget == null) continue;
			int rowY = top + p.y + (ROW_H - 16) / 2;
			boolean onScreen = p.shown && rowY + 16 > viewport.top() && rowY < viewport.bottom();
			if (p.widget instanceof NTextField field) {
				field.setFramePosition(field.getX() - 5, rowY);
			} else {
				p.widget.setY(rowY);
			}
			p.widget.visible = onScreen;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (!viewport.containsPoint((int) mouseX, (int) mouseY)) {
			return false;
		}
		double max = Math.max(0, contentHeight - viewport.height());
		scroll = Mth.clamp(scroll - amount * 24, 0, max);
		positionWidgets();
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = screen.font();
		int accent = Theme.accent();
		Theme.card(g, viewport.left(), viewport.top(), viewport.width(), viewport.height(), Theme.CARD, Theme.LINE);
		g.enableScissor(viewport.left() + 1, viewport.top() + 1, viewport.right() - 1, viewport.bottom() - 1);
		int top = viewport.top() - (int) scroll;
		Placed hoveredRow = null;
		boolean anyShown = false;
		for (Placed p : placed) {
			if (!p.shown) continue;
			anyShown = true;
			int rowY = top + p.y;
			if (rowY > viewport.bottom() || rowY + ROW_H < viewport.top()) continue;
			if (p.row.isHeader()) {
				Theme.textScaled(g, font, p.row.section().toUpperCase(Locale.ROOT), x + 8, rowY + 7, 0.75f, accent, false);
				int lineX = x + 12 + (int) (font.width(p.row.section().toUpperCase(Locale.ROOT)) * 0.75f);
				g.fill(lineX, rowY + 10, x + w - 12, rowY + 11, Theme.LINE);
				continue;
			}
			boolean hovered = mouseX >= x && mouseX < x + w - 8 && mouseY >= rowY && mouseY < rowY + ROW_H
				&& viewport.containsPoint(mouseX, mouseY);
			if (hovered) {
				Theme.round(g, x + 3, rowY + 1, w - 14, ROW_H - 2, Theme.alpha(Theme.CARD_HOVER, 0.7f));
				if (mouseX < x + labelW) {
					hoveredRow = p;
				}
			}
			Theme.text(g, font, Theme.ellipsize(font, p.row.label(), labelW - 18), x + 10, rowY + (ROW_H - 8) / 2,
				hovered ? Theme.TEXT : Theme.TEXT_DIM);
			if (!p.row.help().isEmpty()) {
				int qx = x + labelW - 12;
				Theme.text(g, font, "?", qx, rowY + (ROW_H - 8) / 2, hovered ? accent : Theme.TEXT_FAINT);
			}
			if (p.widget != null && p.widget.visible) {
				p.widget.render(g, mouseX, mouseY, partialTick);
			}
		}
		if (!anyShown) {
			Theme.textCenter(g, font, "No settings match \"" + filter + "\"", viewport.left() + viewport.width() / 2,
				viewport.top() + 20, Theme.TEXT_FAINT);
		}
		g.disableScissor();

		int max = contentHeight - viewport.height();
		if (max > 0) {
			int trackX = viewport.right() - 5;
			int trackTop = viewport.top() + 3;
			int trackH = viewport.height() - 6;
			int thumbH = Math.max(16, trackH * viewport.height() / contentHeight);
			int thumbY = trackTop + (int) ((trackH - thumbH) * (scroll / max));
			g.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x22FFFFFF);
			g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, Theme.alpha(accent, 0.8f));
		}

		if (hoveredRow != null && !hoveredRow.row.help().isEmpty()) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(hoveredRow.row.label()).withColor(accent));
			for (var line : font.split(Component.literal(hoveredRow.row.help()), 220)) {
				StringBuilder sb = new StringBuilder();
				line.accept((index, style, codePoint) -> {
					sb.appendCodePoint(codePoint);
					return true;
				});
				lines.add(Component.literal(sb.toString()).withColor(Theme.TEXT_DIM));
			}
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	// ------------------------------------------------------------------ rows

	private List<Row> buildRows() {
		List<Row> rows = new ArrayList<>();

		section(rows, "Cycle & search");
		row(rows, "Cycle length", "How long each cycle lasts. At the end of every cycle the bot runs /sell and pays the balance out.",
			slider(1, 30, 0.5, () -> c().cycleSeconds / 60.0, v -> c().cycleSeconds = (int) Math.round(v * 60),
				v -> (v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v)) + " min"));
		row(rows, "Sand needed", "Keep using RTP until MORE than this many sand blocks with nothing above them are within the scan radius.",
			slider(100, 3000, 50, () -> c().sandThreshold, v -> c().sandThreshold = (int) v, v -> "> " + (int) v));
		row(rows, "Scan radius", "Count sand in every chunk within this many chunks of the bot (a circle). Your render distance must be at least this.",
			slider(1, 8, 1, () -> c().scanRadiusChunks, v -> c().scanRadiusChunks = (int) v, v -> (int) v + " ch"));
		row(rows, "RTP every cycle", "Teleport to a fresh area at the start of every cycle. Off = stay while the area still has enough sand.",
			toggle(() -> c().rtpEveryCycle, v -> c().rtpEveryCycle = v));
		row(rows, "RTP when depleted", "If no reachable sand is left mid-cycle, search for a new area instead of idling.",
			toggle(() -> c().rtpWhenDepleted, v -> c().rtpWhenDepleted = v));
		row(rows, "Include red sand", "Also count, mine and sell red sand.",
			toggle(() -> c().includeRedSand, v -> c().includeRedSand = v));

		section(rows, "Teleport");
		row(rows, "RTP command", "Command used to random teleport (no slash).",
			text(() -> c().rtpCommand, v -> c().rtpCommand = v, "rtp"));
		row(rows, "RTP menu option", "If the RTP command opens a menu, the bot clicks the item whose name contains this text. Separate alternatives with commas. The log lists the menu's options if nothing matches.",
			text(() -> c().rtpMenuKeyword, v -> c().rtpMenuKeyword = v, "overworld"));
		row(rows, "Teleport timeout", "Give up on an RTP attempt if no teleport happens within this time.",
			slider(5, 60, 1, () -> c().rtpTimeoutSeconds, v -> c().rtpTimeoutSeconds = (int) v, v -> (int) v + "s"));
		row(rows, "Delay between RTPs", "Pause between RTP attempts (cooldown messages from the server are detected automatically).",
			slider(0, 60, 1, () -> c().rtpDelaySeconds, v -> c().rtpDelaySeconds = (int) v, v -> (int) v + "s"));
		row(rows, "Chunk load wait", "Maximum time to wait for chunks to load after landing before scanning.",
			slider(2, 20, 1, () -> c().chunkWaitSeconds, v -> c().chunkWaitSeconds = (int) v, v -> (int) v + "s"));

		section(rows, "Mining");
		row(rows, "Reach", "How far away the bot mines blocks from (vanilla survival reach is 4.5).",
			slider(2.5, 4.5, 0.1, () -> c().reach, v -> c().reach = v, v -> String.format(Locale.ROOT, "%.1f", v)));
		row(rows, "Break delay", "Ticks to wait between broken blocks (20 ticks = 1 second).",
			slider(0, 10, 1, () -> c().breakDelayTicks, v -> c().breakDelayTicks = (int) v, v -> (int) v + "t"));
		row(rows, "Turn speed", "Maximum degrees the camera turns per tick.",
			slider(10, 180, 5, () -> c().rotationSpeed, v -> c().rotationSpeed = (int) v, v -> (int) v + "\u00b0"));
		row(rows, "Sprint", "Sprint while walking between sand patches (uses more hunger).",
			toggle(() -> c().sprint, v -> c().sprint = v));
		row(rows, "Collect radius", "Walk over dropped items within this many blocks. 0 = never collect.",
			slider(0, 12, 1, () -> c().collectRadius, v -> c().collectRadius = (int) v, v -> v == 0 ? "Off" : (int) v + "m"));
		row(rows, "Keep tools above", "Never use a shovel with less durability left than this.",
			slider(0, 200, 1, () -> c().minToolDurability, v -> c().minToolDurability = (int) v, v -> String.valueOf((int) v)));
		row(rows, "Mine by hand", "Mine without a shovel when none is usable (slow).",
			toggle(() -> c().allowHandMining, v -> c().allowHandMining = v));
		row(rows, "Sell when full", "Sell as soon as the inventory fills up mid-cycle, then keep mining.",
			toggle(() -> c().sellWhenFull, v -> c().sellWhenFull = v));
		row(rows, "Human-like timing", "Small random variation in turn speed and break delay.",
			toggle(() -> c().humanize, v -> c().humanize = v));

		section(rows, "Looting");
		row(rows, "Pick up all items", "Walk to every dropped item within the collect radius, not just sand.",
			toggle(() -> c().pickupAllItems, v -> c().pickupAllItems = v));
		row(rows, "Raid containers", "Walk to nearby chests, trapped chests, barrels and shulker boxes, open them and take what's inside.",
			toggle(() -> c().raidChests, v -> c().raidChests = v));
		row(rows, "Raid radius", "How far (in blocks) to look for containers to raid.",
			slider(2, 24, 1, () -> c().raidRadius, v -> c().raidRadius = (int) v, v -> (int) v + "m"));
		row(rows, "Only take", "Comma separated words; only items whose name contains one are taken. Leave empty to take everything.",
			text(() -> c().raidFilter, v -> c().raidFilter = v, "everything"));
		row(rows, "Raid again after", "Minutes before an already looted container is checked again (for chests that get refilled). 0 = once.",
			slider(0, 120, 5, () -> c().raidRefillMinutes, v -> c().raidRefillMinutes = (int) v, v -> v == 0 ? "Never" : (int) v + " min"));
		row(rows, "Sell loot", "Also put picked-up and looted items into /sell. Tools, weapons, armor, food and the keep list are never sold.",
			toggle(() -> c().sellLoot, v -> c().sellLoot = v));
		row(rows, "Never sell", "Comma separated words; items whose name contains one are always kept.",
			text(() -> c().keepItems, v -> c().keepItems = v, "shulker, totem, elytra"));
		row(rows, "Drop unsellable", "Throw away items the server wouldn't buy (and stop picking them up) so they don't fill the inventory.",
			toggle(() -> c().dropUnsellable, v -> c().dropUnsellable = v));

		section(rows, "Selling");
		row(rows, "Sell mode", "GUI menu: run the sell command, shift-click all sand into the menu and close it. Command only: just run the command.",
			cycle(List.of(BotConfig.SellMode.values()), () -> c().sellMode, v -> c().sellMode = v, m -> m.label));
		row(rows, "Sell command", "Command that sells items (no slash).",
			text(() -> c().sellCommand, v -> c().sellCommand = v, "sell"));
		row(rows, "Sell button", "Optional: click the menu item containing this text after moving the sand in, instead of just closing the menu.",
			text(() -> c().sellConfirmKeyword, v -> c().sellConfirmKeyword = v, "(close menu)"));
		row(rows, "Click delay", "Ticks between shift-clicks in the sell menu and in raided containers.",
			slider(0, 10, 1, () -> c().sellClickDelayTicks, v -> c().sellClickDelayTicks = (int) v, v -> (int) v + "t"));

		section(rows, "Payout");
		row(rows, "Pay out", "After selling at the end of each cycle, pay the balance to the player below.",
			toggle(() -> c().payEnabled, v -> c().payEnabled = v));
		row(rows, "Pay to player", "Username that receives the money.",
			text(() -> c().payTarget, v -> c().payTarget = v.trim(), "Username"));
		row(rows, "Balance command", "Command that shows your balance (no slash). The scoreboard is used as a fallback.",
			text(() -> c().balanceCommand, v -> c().balanceCommand = v, "bal"));
		row(rows, "Pay command", "Payment command; {player} and {amount} are replaced.",
			text(() -> c().payCommand, v -> c().payCommand = v, "pay {player} {amount}"));
		row(rows, "Keep in balance", "Money to leave on this account after paying (e.g. 0, 5000 or 2.5k).",
			money(() -> c().keepBalance, v -> c().keepBalance = v));
		row(rows, "Minimum payout", "Skip paying when the amount would be below this.",
			money(() -> c().minPayout, v -> c().minPayout = v));
		row(rows, "Confirm button", "If /pay opens a confirmation menu, click the item containing this text.",
			text(() -> c().payConfirmKeyword, v -> c().payConfirmKeyword = v, "confirm"));
		row(rows, "Retry held payout", "If the server refuses a payout because the player is offline, the money is held and paid the moment they appear in the player list. Otherwise it is retried this often.",
			slider(1, 60, 1, () -> c().payRetryMinutes, v -> c().payRetryMinutes = (int) v, v -> (int) v + " min"));

		section(rows, "Safety");
		row(rows, "Auto eat", "Eat food from the inventory when hungry.",
			toggle(() -> c().autoEat, v -> c().autoEat = v));
		row(rows, "Eat below hunger", "Start eating at or below this hunger level (20 = full).",
			slider(1, 19, 1, () -> c().eatBelowHunger, v -> c().eatBelowHunger = (int) v, v -> (int) v + "/20"));
		row(rows, "Fight hostile mobs", "Hit hostile mobs (husks, zombies, spiders...) that get within reach.",
			toggle(() -> c().defendMobs, v -> c().defendMobs = v));
		row(rows, "Low health at", "Health (in hearts) that triggers the low health action.",
			slider(1, 19, 1, () -> c().lowHealth, v -> c().lowHealth = (int) v, v -> String.format(Locale.ROOT, "%.1f hp", v / 2)));
		row(rows, "On low health", "What to do when health drops below the threshold.",
			cycle(List.of(BotConfig.DangerAction.WARN, BotConfig.DangerAction.PAUSE, BotConfig.DangerAction.RTP, BotConfig.DangerAction.STOP,
				BotConfig.DangerAction.DISCONNECT), () -> c().lowHealthAction, v -> c().lowHealthAction = v, a -> a.label));
		row(rows, "Player alert radius", "React when another player comes this close. 0 = off.",
			slider(0, 64, 1, () -> c().playerAlertRadius, v -> c().playerAlertRadius = (int) v, v -> v == 0 ? "Off" : (int) v + "m"));
		row(rows, "On nearby player", "Reaction to a player inside the alert radius. Pause resumes automatically once they leave.",
			cycle(List.of(BotConfig.DangerAction.values()), () -> c().playerNearbyAction, v -> c().playerNearbyAction = v, a -> a.label));
		row(rows, "Ignored players", "Comma separated names that never trigger alerts (the payout player is always ignored).",
			text(() -> c().ignoredPlayers, v -> c().ignoredPlayers = v, "Name1, Name2"));
		row(rows, "Auto reconnect", "Rejoin the server after a kick or lost connection and restart the bot.",
			toggle(() -> c().autoReconnect, v -> c().autoReconnect = v));
		row(rows, "Reconnect delay", "Seconds to wait before reconnecting.",
			slider(5, 120, 5, () -> c().reconnectDelaySeconds, v -> c().reconnectDelaySeconds = (int) v, v -> (int) v + "s"));
		row(rows, "Run in background", "Don't open the pause menu when the game window loses focus while the bot runs.",
			toggle(() -> c().keepRunningUnfocused, v -> c().keepRunningUnfocused = v));

		section(rows, "Automation");
		row(rows, "Respawn after death", "Click Respawn automatically and restart the bot after dying.",
			toggle(() -> c().autoRespawn, v -> c().autoRespawn = v));
		row(rows, "Python runner bridge", "Lets run_sandbot.py watch and control the bot through nunkin-status.json and nunkin-control.json in the config folder. The runner turns this on by itself.",
			toggle(() -> c().remoteControl, v -> c().remoteControl = v));

		section(rows, "Interface");
		row(rows, "Show HUD", "Show the bot status panel on screen while it runs.",
			toggle(() -> c().showHud, v -> c().showHud = v));
		row(rows, "HUD position", "Screen corner for the HUD panel.",
			cycle(List.of(BotConfig.HudCorner.values()), () -> c().hudCorner, v -> c().hudCorner = v, corner -> corner.label));
		row(rows, "HUD scale", "Size of the HUD panel.",
			slider(0.5, 1.5, 0.05, () -> c().hudScale, v -> c().hudScale = v, v -> String.format(Locale.ROOT, "%.2fx", v)));
		row(rows, "Accent color", "Highlight color used across the panel and HUD.",
			cycle(IntStream.range(0, Theme.accentCount()).boxed().toList(), () -> Math.floorMod(c().accent, Theme.accentCount()),
				v -> c().accent = v, i -> Theme.ACCENT_NAMES[i]));
		row(rows, "Chat messages", "Print important bot events in chat (only you can see them).",
			toggle(() -> c().chatFeedback, v -> c().chatFeedback = v));
		row(rows, "Toast popups", "Show pop-up notifications for important events.",
			toggle(() -> c().toasts, v -> c().toasts = v));
		return rows;
	}

	private void section(List<Row> rows, String name) {
		currentSection = name;
		rows.add(new Row(name, "", "", null));
	}

	private void row(List<Row> rows, String label, String help, Factory factory) {
		rows.add(new Row(currentSection, label, help, factory));
	}

	private static Factory toggle(BooleanSupplier getter, Consumer<Boolean> setter) {
		return (x, y, w, h, clip) -> new NToggle(x, y, w, h, getter, v -> {
			setter.accept(v);
			ConfigManager.markDirty();
		}).clip(clip);
	}

	private static Factory slider(double min, double max, double step, DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> format) {
		return (x, y, w, h, clip) -> new NSlider(x, y, w, h, min, max, step, getter, setter, format).clip(clip);
	}

	private <E> Factory cycle(List<E> values, Supplier<E> getter, Consumer<E> setter, Function<E, String> labeler) {
		return (x, y, w, h, clip) -> {
			NWidget widget = new NCycle<>(x, y, w, h, values, getter, setter, labeler);
			return widget.clip(clip);
		};
	}

	private Factory text(Supplier<String> getter, Consumer<String> setter, String hint) {
		return (x, y, w, h, clip) -> new NTextField(screen.font(), x, y, w, h, getter.get(), hint, v -> {
			setter.accept(v);
			ConfigManager.markDirty();
		}).clip(clip);
	}

	private Factory money(DoubleSupplier getter, DoubleConsumer setter) {
		return (x, y, w, h, clip) -> new NTextField(screen.font(), x, y, w, h, TextUtil.plainAmount(getter.getAsDouble()), "0", v -> {
			if (v.isBlank()) {
				setter.accept(0);
			} else {
				TextUtil.Money parsed = TextUtil.parseMoneyInput(v);
				if (parsed != null) {
					setter.accept(parsed.value());
				}
			}
			ConfigManager.markDirty();
		}).clip(clip);
	}
}
