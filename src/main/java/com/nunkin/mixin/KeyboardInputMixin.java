package com.nunkin.mixin;

import com.nunkin.bot.control.InputController;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void nunkin$applyBotInput(CallbackInfo ci) {
		if (!InputController.isActive()) {
			return;
		}
		this.keyPresses = new Input(
			InputController.forward,
			InputController.back,
			InputController.left,
			InputController.right,
			InputController.jump,
			InputController.sneak,
			InputController.sprint
		);
		float forward = InputController.forward == InputController.back ? 0f : (InputController.forward ? 1f : -1f);
		float strafe = InputController.left == InputController.right ? 0f : (InputController.left ? 1f : -1f);
		this.moveVector = new Vec2(strafe, forward).normalized();
	}
}
