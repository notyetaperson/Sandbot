package com.nunkin.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * File-based link to the Python runner ({@code run_sandbot.py}): writes {@code config/nunkin-status.json} every two
 * seconds and executes commands written to {@code config/nunkin-control.json}. Only active while the
 * "Python runner bridge" setting is on.
 */
public final class RemoteBridge {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path STATUS = DIR.resolve("nunkin-status.json");
	private static final Path STATUS_TMP = DIR.resolve("nunkin-status.json.tmp");
	private static final Path CONTROL = DIR.resolve("nunkin-control.json");
	private static final long STARTED_AT = System.currentTimeMillis();
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int EVENTS_IN_STATUS = 40;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Nunkin runner bridge");
		thread.setDaemon(true);
		return thread;
	});

	private static boolean active;
	private static long lastStatusWrite;
	private static long lastControlPoll;
	private static long lastCommandId = Long.MIN_VALUE;
	private static long ackId = -1;
	private static String ackCommand = "";
	private static String ackResult = "";
	private static long ackTime;

	private RemoteBridge() {
	}

	public static void tick(Minecraft mc) {
		boolean enabled = ConfigManager.get().remoteControl;
		if (!enabled) {
			if (active) {
				active = false;
				writeStatus(mc, "disabled");
			}
			return;
		}
		active = true;
		long now = System.currentTimeMillis();
		if (now - lastControlPoll >= 500) {
			lastControlPoll = now;
			pollControl(mc);
		}
		if (now - lastStatusWrite >= 2000) {
			lastStatusWrite = now;
			writeStatus(mc, "running");
		}
	}

	/** Final status so the runner knows the game is closing rather than frozen. */
	public static void shutdown() {
		if (active) {
			writeStatus(Minecraft.getInstance(), "closing");
		}
		IO.shutdown();
		try {
			IO.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void pollControl(Minecraft mc) {
		JsonObject command;
		long id;
		try {
			if (!Files.exists(CONTROL)) {
				if (lastCommandId == Long.MIN_VALUE) {
					lastCommandId = 0;
				}
				return;
			}
			long modified = Files.getLastModifiedTime(CONTROL).toMillis();
			command = JsonParser.parseString(Files.readString(CONTROL)).getAsJsonObject();
			id = command.has("id") ? command.get("id").getAsLong() : 0;
			if (lastCommandId == Long.MIN_VALUE) {
				// Commands left over from before this game session started are not replayed.
				lastCommandId = modified < STARTED_AT ? id : Long.MIN_VALUE + 1;
			}
		} catch (Exception e) {
			// Usually a half-written file; it is read again on the next poll.
			return;
		}
		if (id <= lastCommandId) {
			return;
		}
		lastCommandId = id;
		String name = string(command, "command", "").toLowerCase(Locale.ROOT);
		JsonObject args = command.has("args") && command.get("args").isJsonObject() ? command.getAsJsonObject("args") : new JsonObject();
		String result;
		try {
			result = execute(mc, name, args);
		} catch (Exception e) {
			result = "error: " + e.getMessage();
		}
		ackId = id;
		ackCommand = name;
		ackResult = result;
		ackTime = System.currentTimeMillis();
		BotLog.info("Runner command \"" + name + "\": " + result);
		lastStatusWrite = 0;
	}

	private static String execute(Minecraft mc, String command, JsonObject args) {
		SandBot bot = SandBot.get();
		return switch (command) {
			case "ping" -> "pong";
			case "start" -> {
				if (mc.player == null) yield "not-in-world";
				if (mc.player.isDeadOrDying()) yield "dead";
				bot.start();
				yield bot.isRunning() ? "ok" : "failed";
			}
			case "stop" -> {
				bot.stop("Stopped from the runner", SandBot.StopKind.USER);
				yield "ok";
			}
			case "pause" -> {
				bot.setPaused(true, "Paused from the runner");
				yield bot.isPaused() ? "ok" : "not-running";
			}
			case "resume" -> {
				bot.setPaused(false, null);
				yield bot.isRunning() && !bot.isPaused() ? "ok" : "not-running";
			}
			case "sell" -> bot.forceSell() ? "ok" : "busy";
			case "rtp" -> bot.forceRtp() ? "ok" : "busy";
			case "pay" -> bot.forcePay() ? "ok" : "busy";
			case "endcycle" -> bot.endCycleNow() ? "ok" : "not-running";
			case "respawn" -> bot.respawn(mc) ? "ok" : "not-dead";
			case "join" -> {
				String address = string(args, "server", Reconnector.lastServerAddress()).trim();
				if (address.isEmpty()) yield "no-server";
				if (mc.level != null) yield "already-in-world";
				if (mc.screen instanceof ConnectScreen) yield "connecting";
				Reconnector.connect(mc, address);
				yield "ok";
			}
			case "disconnect" -> {
				if (mc.level == null) yield "not-in-world";
				bot.stop("Disconnected from the runner", SandBot.StopKind.USER);
				Reconnector.suppressNext();
				mc.disconnectFromWorld(Component.literal("Disconnected by the SandBot runner"));
				yield "ok";
			}
			case "set-pay-target" -> {
				String player = string(args, "player", "").trim();
				if (!PLAYER_NAME.matcher(player).matches()) yield "invalid-name";
				BotConfig cfg = ConfigManager.get();
				cfg.payTarget = player;
				cfg.payEnabled = true;
				ConfigManager.save();
				yield "ok";
			}
			case "quit" -> {
				bot.stop("Game closed by the runner", SandBot.StopKind.RECOVERABLE);
				ConfigManager.save();
				mc.stop();
				yield "ok";
			}
			default -> "unknown-command";
		};
	}

	private static void writeStatus(Minecraft mc, String state) {
		SandBot bot = SandBot.get();
		BotConfig cfg = ConfigManager.get();
		BotStats stats = bot.stats();

		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("state", state);
		root.addProperty("pid", ProcessHandle.current().pid());
		root.addProperty("updated", System.currentTimeMillis());
		root.addProperty("startedAt", STARTED_AT);
		root.addProperty("account", mc.getUser().getName());
		root.addProperty("inWorld", mc.level != null && mc.player != null);
		root.addProperty("screen", screenName(mc));
		ServerData server = mc.getCurrentServer();
		root.addProperty("server", server != null ? server.ip : "");
		if (mc.player != null) {
			root.addProperty("health", mc.player.getHealth());
			root.addProperty("food", mc.player.getFoodData().getFoodLevel());
			root.addProperty("dead", mc.player.isDeadOrDying() || mc.screen instanceof DeathScreen);
		}

		JsonObject botJson = new JsonObject();
		botJson.addProperty("running", bot.isRunning());
		botJson.addProperty("paused", bot.isPaused());
		botJson.addProperty("phase", bot.phase().name());
		botJson.addProperty("detail", bot.detail());
		botJson.addProperty("cycle", bot.cycleIndex());
		botJson.addProperty("cycleRemainingSeconds", bot.cycleRemainingMs() / 1000);
		botJson.addProperty("cycleSeconds", cfg.cycleSeconds);
		botJson.addProperty("stopKind", bot.stopKind() == null ? "" : bot.stopKind().name());
		botJson.addProperty("stopReason", bot.stopReason());
		botJson.addProperty("stopTime", bot.stopTime());
		root.add("bot", botJson);

		JsonObject payout = new JsonObject();
		payout.addProperty("enabled", cfg.payEnabled);
		payout.addProperty("target", cfg.payTarget);
		payout.addProperty("targetOnline", bot.isPayeeOnline());
		payout.addProperty("pending", stats.pendingPayout);
		payout.addProperty("pendingSince", stats.pendingSince);
		root.add("payout", payout);

		JsonObject reconnect = new JsonObject();
		reconnect.addProperty("waiting", Reconnector.isWaiting());
		reconnect.addProperty("seconds", Reconnector.secondsUntilReconnect());
		root.add("reconnect", reconnect);

		JsonObject statsJson = new JsonObject();
		statsJson.add("session", GSON.toJsonTree(stats.session));
		statsJson.add("lifetime", GSON.toJsonTree(stats.lifetime));
		root.add("stats", statsJson);

		JsonArray events = new JsonArray();
		List<BotLog.Entry> entries = BotLog.snapshot();
		for (BotLog.Entry entry : entries.subList(Math.max(0, entries.size() - EVENTS_IN_STATUS), entries.size())) {
			JsonObject event = new JsonObject();
			event.addProperty("id", entry.id());
			event.addProperty("time", entry.clock());
			event.addProperty("level", entry.level().name());
			event.addProperty("message", entry.message());
			events.add(event);
		}
		root.add("events", events);

		JsonObject ack = new JsonObject();
		ack.addProperty("id", ackId);
		ack.addProperty("command", ackCommand);
		ack.addProperty("result", ackResult);
		ack.addProperty("time", ackTime);
		root.add("ack", ack);

		String json = GSON.toJson(root);
		if (IO.isShutdown()) {
			return;
		}
		IO.execute(() -> {
			try {
				Files.writeString(STATUS_TMP, json);
				try {
					Files.move(STATUS_TMP, STATUS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(STATUS_TMP, STATUS, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				// The runner may be reading the file at this moment (Windows locks it); the next write retries.
			}
		});
	}

	private static String screenName(Minecraft mc) {
		Screen screen = mc.screen;
		if (screen == null) return mc.level == null ? "none" : "game";
		if (screen instanceof BotScreen) return "panel";
		if (screen instanceof DeathScreen) return "death";
		if (screen instanceof DisconnectedScreen) return "disconnected";
		if (screen instanceof ConnectScreen) return "connecting";
		if (screen instanceof TitleScreen) return "title";
		if (screen instanceof JoinMultiplayerScreen) return "multiplayer";
		if (screen instanceof PauseScreen) return "pause";
		if (screen instanceof AbstractContainerScreen<?>) return "container";
		return "other";
	}

	private static String string(JsonObject obj, String key, String fallback) {
		JsonElement element = obj.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
	}
}
