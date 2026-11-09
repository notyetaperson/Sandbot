package com.nunkin.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nunkin.NunkinClient;
import com.nunkin.bot.BotLog;
import com.nunkin.bot.BotStats;
import com.nunkin.bot.SandBot;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end check in a flat singleplayer world: builds an 81x81 sand field, exercises every UI tab and the
 * pause-menu integration, then runs one full bot cycle with stand-in commands for /rtp, /sell, /bal and /pay.
 */
public class SandBotClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("nunkin-test");

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			TestServerContext server = singleplayer.getServer();
			singleplayer.getClientWorld().waitForChunksRender();

			server.runCommand("gamemode survival @a");
			server.runCommand("difficulty easy");
			server.runCommand("fill -40 -61 -40 40 -61 40 sandstone");
			server.runCommand("fill -40 -60 -40 40 -58 40 sand");
			server.runCommand("setblock 6 -57 6 cactus");
			server.runCommand("setblock 6 -56 6 cactus");
			server.runCommand("setblock -5 -57 3 dead_bush");
			server.runCommand("setblock 12 -57 -9 short_grass");
			server.runCommand("setblock 5 -57 -4 chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:4b,id:\"minecraft:gold_ingot\",count:12}]}");
			server.runCommand("setblock -6 -57 -3 barrel{Items:[{Slot:0b,id:\"minecraft:emerald\",count:8},{Slot:1b,id:\"minecraft:rotten_flesh\",count:4}]}");
			server.runCommand("give @a diamond_shovel");
			server.runCommand("give @a bread 8");
			server.runCommand("effect give @a hunger 20 0 true");
			server.runCommand("tp @a 120 -60 120");
			context.waitTicks(40);
			singleplayer.getClientWorld().waitForChunksRender(true, 1200);

			// Default commands (/rtp menu, /sell GUI, /bal, /pay) are served by FakeDonutServer.
			context.runOnClient(mc -> {
				ConfigManager.resetToDefaults();
				BotConfig c = ConfigManager.get();
				c.cycleSeconds = 75;
				c.scanRadiusChunks = 4;
				c.rtpEveryCycle = false;
				c.payTarget = "TestFriend";
				c.playerAlertRadius = 0;
				c.eatBelowHunger = 19;
				c.remoteControl = true;
				c.payRetryMinutes = 60;
				ConfigManager.save();
			});
			FakeDonutServer.payeeOnline = false;

			// UI: every tab while idle.
			context.setScreen(() -> new BotScreen(null));
			context.waitTicks(10);
			for (BotScreen.Tab tab : BotScreen.Tab.values()) {
				context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(tab));
				context.waitTicks(4);
				context.takeScreenshot("idle_" + tab.name().toLowerCase());
			}
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.DASHBOARD));
			context.setScreen(() -> null);

			// Pause menu button.
			context.setScreen(() -> new PauseScreen(true));
			context.waitTicks(5);
			context.takeScreenshot("pause_menu");
			context.setScreen(() -> null);
			context.waitTicks(2);

			// B opens and closes the panel.
			context.getInput().pressKey(NunkinClient.openKey());
			context.waitForScreen(BotScreen.class);
			context.waitTicks(6);
			context.takeScreenshot("opened_with_b");
			context.getInput().pressKey(NunkinClient.openKey());
			context.waitForScreen(null);

			// Run the bot: search (fake RTP), mine, loot, sell, pay.
			context.runOnClient(mc -> SandBot.get().start());
			context.waitFor(mc -> mc.player != null && mc.player.blockPosition().distManhattan(new BlockPos(0, -57, 0)) < 4, 400);
			server.runCommand("summon item 3 -57 3 {Item:{id:\"minecraft:diamond\",count:3}}");
			for (int i = 0; i < 6; i++) {
				context.waitTicks(200);
				context.takeScreenshot("running_world_" + i);
				logProgress(context, "t+" + (i + 1) * 10 + "s");
			}
			context.setScreen(() -> new BotScreen(null));
			context.waitTicks(6);
			context.takeScreenshot("running_dashboard");
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.RADAR));
			context.waitTicks(6);
			context.takeScreenshot("running_radar");
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.LOG));
			context.waitTicks(6);
			context.takeScreenshot("running_log");
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.DASHBOARD));
			context.setScreen(() -> null);

			// End of the cycle: the recipient is offline, so the payout must be held rather than lost.
			int waited = context.waitFor(mc -> SandBot.get().stats().pendingPayout > 0, 900);
			LOGGER.info("Payout held after {} more ticks", waited);
			context.waitTicks(40);
			context.takeScreenshot("held_payout_dashboard_hud");
			context.setScreen(() -> new BotScreen(null));
			context.waitTicks(6);
			context.takeScreenshot("held_payout_dashboard");
			context.setScreen(() -> null);
			double held = context.computeOnClient(mc -> SandBot.get().stats().pendingPayout);
			if (FakeDonutServer.paidTotal != 0 || held < 1) {
				throw new AssertionError("Expected a held payout, got paid=" + FakeDonutServer.paidTotal + " held=" + held);
			}
			JsonObject status = readStatus();
			if (!"running".equals(status.get("state").getAsString()) || status.getAsJsonObject("payout").get("pending").getAsDouble() < 1) {
				throw new AssertionError("Status file doesn't show the held payout: " + status);
			}

			// The recipient logs in; the Python runner asks the bot to pay now.
			FakeDonutServer.payeeOnline = true;
			Path gameDir = FabricLoader.getInstance().getGameDir();
			Process runner = startRunner(gameDir, "send", "pay", "--game-dir", gameDir.toString());
			context.waitFor(mc -> FakeDonutServer.paidTotal > 0 && SandBot.get().stats().pendingPayout == 0, 600);
			String runnerOutput = finish(runner);
			LOGGER.info("run_sandbot.py send pay -> exit {}: {}", runner.exitValue(), runnerOutput.trim());
			if (runner.exitValue() != 0 || !runnerOutput.contains("ok")) {
				throw new AssertionError("Runner command failed (exit " + runner.exitValue() + "): " + runnerOutput);
			}
			context.waitTicks(60);
			Process statusRunner = startRunner(gameDir, "status", "--game-dir", gameDir.toString());
			context.waitFor(mc -> !statusRunner.isAlive(), 400);
			LOGGER.info("run_sandbot.py status:\n{}", finish(statusRunner));

			context.setScreen(() -> new BotScreen(null));
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.STATS));
			context.waitTicks(6);
			context.takeScreenshot("after_cycle_stats");
			context.runOnClient(mc -> ((BotScreen) mc.screen).switchTab(BotScreen.Tab.LOG));
			context.waitTicks(6);
			context.takeScreenshot("after_cycle_log");
			context.setScreen(() -> null);

			BotStats.Totals totals = context.computeOnClient(mc -> SandBot.get().stats().session);
			logProgress(context, "final");
			context.runOnClient(mc -> SandBot.get().stop("Test finished", SandBot.StopKind.USER));
			context.computeOnClient(mc -> {
				LOGGER.info("==== Bot log ====\n{}", BotLog.export());
				return null;
			});
			LOGGER.info("Fake server: rtpMenus={} sellMenus={} itemsSold={} byItem={} handedBack={} paid={} balanceLeft={}",
				FakeDonutServer.rtpMenusOpened, FakeDonutServer.sellMenusOpened, FakeDonutServer.itemsSold, FakeDonutServer.soldByItem,
				FakeDonutServer.offeredUnsellable, FakeDonutServer.paidTotal, FakeDonutServer.balance);
			if (totals.sandMined < 20) {
				throw new AssertionError("Bot mined only " + totals.sandMined + " sand");
			}
			if (FakeDonutServer.rtpMenusOpened < 1) {
				throw new AssertionError("Bot never used /rtp");
			}
			if (FakeDonutServer.itemsSold < 20 || totals.sandSold != FakeDonutServer.itemsSold) {
				throw new AssertionError("Sell mismatch: server sold " + FakeDonutServer.itemsSold + ", bot counted " + totals.sandSold);
			}

			// Looting: both containers emptied, the dropped diamonds collected, the loot sold, protected items kept.
			boolean containersEmpty = server.computeOnServer(s -> ((Container) s.overworld().getBlockEntity(new BlockPos(5, -57, -4))).isEmpty()
				&& ((Container) s.overworld().getBlockEntity(new BlockPos(-6, -57, -3))).isEmpty());
			int diamondsSold = FakeDonutServer.soldByItem.getOrDefault(Items.DIAMOND, 0);
			boolean keptTools = context.computeOnClient(mc -> mc.player.getInventory().contains(new ItemStack(Items.DIAMOND_SHOVEL))
				&& mc.player.getInventory().contains(new ItemStack(Items.BREAD)));
			boolean learnedFlesh = context.computeOnClient(mc -> SandBot.get().unsellableItems().contains(Items.ROTTEN_FLESH));
			LOGGER.info("Looting: raided={} looted={} containersEmpty={} diamondsSold={} goldSold={} emeraldsSold={} keptTools={} learnedFlesh={}",
				totals.chestsRaided, totals.itemsLooted, containersEmpty, diamondsSold, FakeDonutServer.soldByItem.getOrDefault(Items.GOLD_INGOT, 0),
				FakeDonutServer.soldByItem.getOrDefault(Items.EMERALD, 0), keptTools, learnedFlesh);
			if (totals.chestsRaided < 2 || !containersEmpty) {
				throw new AssertionError("Containers not raided: raided=" + totals.chestsRaided + " empty=" + containersEmpty);
			}
			if (diamondsSold != 8 || FakeDonutServer.soldByItem.getOrDefault(Items.GOLD_INGOT, 0) != 12
				|| FakeDonutServer.soldByItem.getOrDefault(Items.EMERALD, 0) != 8) {
				throw new AssertionError("Loot not sold as expected: " + FakeDonutServer.soldByItem);
			}
			if (!keptTools || FakeDonutServer.offeredUnsellable.contains(Items.DIAMOND_SHOVEL) || FakeDonutServer.offeredUnsellable.contains(Items.BREAD)) {
				throw new AssertionError("Protected items were offered for sale or lost");
			}
			if (!learnedFlesh) {
				throw new AssertionError("Rotten flesh should have been learned as unsellable");
			}
			if (FakeDonutServer.paidTotal <= 0 || FakeDonutServer.balance >= 1 || totals.paid != FakeDonutServer.paidTotal) {
				throw new AssertionError("Payout incomplete: paid " + FakeDonutServer.paidTotal + " (bot counted " + totals.paid
					+ "), left " + FakeDonutServer.balance);
			}
		}
	}

	private static JsonObject readStatus() {
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("nunkin-status.json");
			return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		} catch (IOException e) {
			throw new AssertionError("No runner status file", e);
		}
	}

	/** Starts run_sandbot.py from the project root (the game dir is build/run/clientGameTest). */
	private static Process startRunner(Path gameDir, String... args) {
		Path script = gameDir.toAbsolutePath().getParent().getParent().getParent().resolve("run_sandbot.py");
		for (String python : new String[]{"python", "py"}) {
			List<String> command = new ArrayList<>(List.of(python, script.toString()));
			command.addAll(List.of(args));
			try {
				return new ProcessBuilder(command).redirectErrorStream(true).start();
			} catch (IOException e) {
				LOGGER.warn("Could not start {}: {}", python, e.getMessage());
			}
		}
		throw new AssertionError("Python is needed to run run_sandbot.py");
	}

	private static String finish(Process process) {
		try {
			if (!process.waitFor(20, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
			return new String(process.getInputStream().readAllBytes());
		} catch (IOException | InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static void logProgress(ClientGameTestContext context, String label) {
		context.runOnClient(mc -> {
			SandBot bot = SandBot.get();
			BotStats.Totals t = bot.stats().session;
			LOGGER.info("[{}] phase={} detail='{}' mined={} sold={} paid={} rtps={} pos={}", label, bot.phase(), bot.detail(),
				t.sandMined, t.sandSold, t.paid, t.rtps, mc.player == null ? "?" : mc.player.blockPosition().toShortString());
		});
	}
}
