package com.nunkin.bot.control;

import com.nunkin.config.BotConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Smoothly turns the player toward the highest-priority rotation requested this tick. */
public final class RotationController {
	public static final int PRIORITY_WALK = 1;
	public static final int PRIORITY_MINE = 2;
	public static final int PRIORITY_ACTION = 3;
	public static final int PRIORITY_COMBAT = 4;

	private final RandomSource random = RandomSource.create();
	private boolean requested;
	private int priority;
	private float yaw;
	private float pitch;

	public void newTick() {
		requested = false;
		priority = 0;
	}

	public void request(float yaw, float pitch, int priority) {
		if (requested && priority < this.priority) {
			return;
		}
		this.requested = true;
		this.priority = priority;
		this.yaw = yaw;
		this.pitch = Mth.clamp(pitch, -90f, 90f);
	}

	public void lookAt(Vec3 from, Vec3 to, int priority) {
		request(yawTo(from, to), pitchTo(from, to), priority);
	}

	public void apply(LocalPlayer player, BotConfig cfg) {
		if (!requested) {
			return;
		}
		float speed = cfg.rotationSpeed;
		if (cfg.humanize) {
			speed *= 0.8f + random.nextFloat() * 0.4f;
		}
		float dYaw = Mth.wrapDegrees(yaw - player.getYRot());
		float dPitch = pitch - player.getXRot();
		player.setYRot(player.getYRot() + step(dYaw, speed));
		player.setXRot(Mth.clamp(player.getXRot() + step(dPitch, speed * 0.8f), -90f, 90f));
	}

	private static float step(float delta, float maxSpeed) {
		float abs = Math.abs(delta);
		if (abs < 1.2f) {
			return delta;
		}
		float eased = Math.max(abs * 0.55f, Math.min(abs, 1.2f));
		return Math.signum(delta) * Math.min(eased, maxSpeed);
	}

	public static float yawTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
	}

	public static float pitchTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		return (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
	}

	public static boolean isFacing(LocalPlayer player, float yaw, float pitch, float tolerance) {
		return Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) <= tolerance && Math.abs(pitch - player.getXRot()) <= tolerance;
	}

	public static float angleTo(LocalPlayer player, float yaw, float pitch) {
		float dy = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
		float dp = Math.abs(pitch - player.getXRot());
		return (float) Math.sqrt(dy * dy + dp * dp);
	}
}
