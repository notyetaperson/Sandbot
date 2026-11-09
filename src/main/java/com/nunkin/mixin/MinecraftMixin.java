package com.nunkin.mixin;

import com.nunkin.bot.SandBot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Vanilla aborts block breaking every tick the attack key is not held; skip that while the bot is mining. */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void nunkin$keepBotMining(boolean attacking, CallbackInfo ci) {
		if (SandBot.get().isAttacking()) {
			ci.cancel();
		}
	}
}
