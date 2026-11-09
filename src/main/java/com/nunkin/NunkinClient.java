package com.nunkin;

import com.mojang.blaze3d.platform.InputConstants;
import com.nunkin.bot.BotLog;
import com.nunkin.bot.ChatWatcher;
import com.nunkin.bot.Reconnector;
import com.nunkin.bot.RemoteBridge;
import com.nunkin.bot.SandBot;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.BotScreen;
import com.nunkin.ui.HudOverlay;
import com.nunkin.ui.PauseMenuIntegration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class NunkinClient implements ClientModInitializer {
	public static final String MOD_ID = "nunkin";
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "sandbot"));

	private static KeyMapping openKey;
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		ConfigManager.load();

		openKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.nunkin.open_panel", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY));
		toggleKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.nunkin.toggle_bot", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(NunkinClient::onEndTick);
		ClientReceiveMessageEvents.GAME.register(ChatWatcher::onGameMessage);
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ChatWatcher.onPlayerChat(message));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Reconnector.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SandBot.get().onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			SandBot.get().stop("Game closing", SandBot.StopKind.RECOVERABLE);
			ConfigManager.save();
			RemoteBridge.shutdown();
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "status"), HudOverlay::render);
		PauseMenuIntegration.register();

		BotLog.info("Nunkin SandBot loaded — press B to open the control panel");
	}

	private static void onEndTick(Minecraft client) {
		while (openKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new BotScreen(null));
			}
		}
		while (toggleKey.consumeClick()) {
			SandBot.get().toggle();
		}
		SandBot.get().onEndTick(client);
		RemoteBridge.tick(client);
	}

	public static KeyMapping openKey() {
		return openKey;
	}
}
