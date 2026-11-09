package com.nunkin.bot;

import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.util.Util;

/** Rejoins the last server after a kick/lost connection and restarts the bot once back in. */
public final class Reconnector {
	private static ServerData lastServer;
	private static boolean armed;
	private static boolean suppressNext;
	private static boolean resumePending;
	private static long disconnectedAt = -1;
	private static long joinedAt = -1;
	private static int attempts;

	private Reconnector() {
	}

	public static void onJoin(Minecraft mc) {
		ServerData data = mc.getCurrentServer();
		if (data != null) {
			lastServer = data;
		}
		if (resumePending) {
			joinedAt = Util.getMillis();
		}
	}

	public static void suppressNext() {
		suppressNext = true;
	}

	public static void onBotDisconnected() {
		if (suppressNext) {
			suppressNext = false;
			return;
		}
		if (ConfigManager.get().autoReconnect && lastServer != null) {
			armed = true;
			resumePending = true;
			attempts = 0;
			disconnectedAt = Util.getMillis();
		}
	}

	/** Connects to {@code address}; it also becomes the server used for auto-reconnect. */
	public static void connect(Minecraft mc, String address) {
		ServerData data = new ServerData(address, address, ServerData.Type.OTHER);
		lastServer = data;
		BotLog.info("Connecting to " + address);
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(address), data, false, null);
	}

	public static String lastServerAddress() {
		return lastServer == null ? "" : lastServer.ip;
	}

	public static void cancel() {
		armed = false;
		resumePending = false;
		joinedAt = -1;
	}

	public static boolean isWaiting() {
		return armed;
	}

	public static long secondsUntilReconnect() {
		if (!armed) return -1;
		long wait = ConfigManager.get().reconnectDelaySeconds * 1000L - (Util.getMillis() - disconnectedAt);
		return Math.max(0, wait / 1000);
	}

	public static void tick(Minecraft mc, SandBot bot) {
		long now = Util.getMillis();
		BotConfig cfg = ConfigManager.get();
		if (resumePending && joinedAt > 0 && mc.player != null && now - joinedAt > 8000) {
			resumePending = false;
			armed = false;
			joinedAt = -1;
			BotLog.info("Reconnected — resuming the bot");
			bot.start();
			return;
		}
		if (!armed || !cfg.autoReconnect || mc.level != null || lastServer == null) {
			return;
		}
		if (!(mc.screen instanceof DisconnectedScreen)) {
			return;
		}
		if (now - disconnectedAt < cfg.reconnectDelaySeconds * 1000L) {
			return;
		}
		attempts++;
		disconnectedAt = now;
		BotLog.info("Reconnecting to " + lastServer.ip + " (attempt " + attempts + ")");
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
	}
}
