package com.nunkin.bot.control;

import com.nunkin.bot.path.Path;
import com.nunkin.bot.world.WorldUtil;
import com.nunkin.config.BotConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Walks a {@link Path} using ordinary movement keys (8 directions, jump, sprint). */
public final class PathExecutor {
	public enum Status {
		IDLE, RUNNING, DONE, STUCK, OFF_PATH
	}

	private Path path;
	private int index;
	private int stuckTicks;
	private double bestDistance;
	private int lastIndex = -1;

	public void follow(Path path) {
		this.path = path;
		this.index = path.size() > 1 ? 1 : 0;
		this.stuckTicks = 0;
		this.bestDistance = Double.MAX_VALUE;
		this.lastIndex = -1;
	}

	public void stop() {
		path = null;
	}

	public Path path() {
		return path;
	}

	public int index() {
		return index;
	}

	public boolean isActive() {
		return path != null;
	}

	public Status tick(LocalPlayer player, ClientLevel level, RotationController rotation, BotConfig cfg) {
		if (path == null) {
			return Status.IDLE;
		}

		BlockPos feet = WorldUtil.feetPos(level, player);
		int lookAhead = Math.min(path.size() - 1, index + 4);
		for (int j = lookAhead; j >= index; j--) {
			if (path.get(j).equals(feet) && (player.onGround() || player.isInWater())) {
				index = j + 1;
				break;
			}
		}
		if (index >= path.size()) {
			path = null;
			return Status.DONE;
		}

		BlockPos target = path.get(index);
		Vec3 pos = player.position();
		double tx = target.getX() + 0.5;
		double tz = target.getZ() + 0.5;
		double dx = tx - pos.x;
		double dz = tz - pos.z;
		double distance = Math.sqrt(dx * dx + dz * dz);

		if (distance < 0.3 && Math.abs(pos.y - target.getY()) < 0.6 && player.onGround()) {
			index++;
			if (index >= path.size()) {
				path = null;
				return Status.DONE;
			}
			target = path.get(index);
			dx = target.getX() + 0.5 - pos.x;
			dz = target.getZ() + 0.5 - pos.z;
			distance = Math.sqrt(dx * dx + dz * dz);
		}

		if (index != lastIndex) {
			lastIndex = index;
			bestDistance = distance;
			stuckTicks = 0;
		} else if (distance < bestDistance - 0.04) {
			bestDistance = distance;
			stuckTicks = 0;
		} else {
			stuckTicks++;
		}
		if (stuckTicks > 50) {
			path = null;
			return Status.STUCK;
		}
		if (distance > 4.5 || pos.y < target.getY() - 4.0) {
			path = null;
			return Status.OFF_PATH;
		}

		float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		rotation.request(desiredYaw, 12f, RotationController.PRIORITY_WALK);

		float relative = Mth.wrapDegrees(desiredYaw - player.getYRot());
		float abs = Math.abs(relative);
		InputController.forward = abs < 67.5f;
		InputController.back = abs > 112.5f;
		InputController.right = relative > 22.5f && relative < 157.5f;
		InputController.left = relative < -22.5f && relative > -157.5f;

		boolean stepUp = target.getY() > feet.getY();
		boolean jump = false;
		if (stepUp && player.onGround() && distance < 1.45) {
			jump = true;
		}
		if (player.isInWater() || player.isInLava()) {
			jump = true;
		}
		if (player.horizontalCollision && player.onGround() && InputController.forward && stuckTicks > 4) {
			jump = true;
		}
		InputController.jump = jump;
		InputController.sprint = cfg.sprint && abs < 20f && path.size() - index > 2 && !player.isInWater();
		return Status.RUNNING;
	}
}
