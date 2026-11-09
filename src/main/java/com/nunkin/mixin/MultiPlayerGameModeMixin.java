package com.nunkin.mixin;

import com.nunkin.bot.SandBot;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	/** Vanilla releases the used item when the use key is up; keep eating while the bot is feeding itself. */
	@Inject(method = "releaseUsingItem", at = @At("HEAD"), cancellable = true)
	private void nunkin$keepEating(Player player, CallbackInfo ci) {
		if (SandBot.get().isEating()) {
			ci.cancel();
		}
	}
}
