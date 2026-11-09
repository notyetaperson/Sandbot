package com.nunkin.bot.path;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public interface Goal {
	double EYE_HEIGHT = 1.62;

	boolean isEnd(int x, int y, int z);

	double heuristic(int x, int y, int z);

	/** Stand somewhere the target block can be seen and reached from. */
	static Goal near(ClientLevel level, LocalPlayer player, BlockPos target, double reach) {
		int tx = target.getX();
		int ty = target.getY();
		int tz = target.getZ();
		Vec3 center = Vec3.atCenterOf(target);
		Vec3 top = new Vec3(tx + 0.5, ty + 1.0, tz + 0.5);
		return new Goal() {
			@Override
			public boolean isEnd(int x, int y, int z) {
				if (x == tx && z == tz) {
					return false;
				}
				if (ty < y - 1 || ty > y + 3) {
					return false;
				}
				Vec3 eye = new Vec3(x + 0.5, y + EYE_HEIGHT, z + 0.5);
				if (eye.distanceTo(center) > reach - 0.35) {
					return false;
				}
				Vec3 aim = eye.y > top.y ? top : center;
				Vec3 end = aim.add(aim.subtract(eye).normalize().scale(0.2));
				BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
				return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
			}

			@Override
			public double heuristic(int x, int y, int z) {
				double dx = x + 0.5 - center.x;
				double dy = y + 0.5 - center.y;
				double dz = z + 0.5 - center.z;
				return Math.max(0, Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz) - (reach - 1.0));
			}
		};
	}

	/** Stand close enough to an item entity resting at {@code itemCell} to pick it up. */
	static Goal pickup(BlockPos itemCell) {
		int ix = itemCell.getX();
		int iy = itemCell.getY();
		int iz = itemCell.getZ();
		return new Goal() {
			@Override
			public boolean isEnd(int x, int y, int z) {
				return Math.abs(x - ix) <= 1 && Math.abs(z - iz) <= 1 && y <= iy && y >= iy - 1;
			}

			@Override
			public double heuristic(int x, int y, int z) {
				double dx = x - ix;
				double dy = y - iy;
				double dz = z - iz;
				return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 1.0);
			}
		};
	}
}
