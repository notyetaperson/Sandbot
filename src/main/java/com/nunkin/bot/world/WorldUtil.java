package com.nunkin.bot.world;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

public final class WorldUtil {
	private WorldUtil() {
	}

	public static boolean isTargetSand(BlockState state, boolean includeRed) {
		return state.is(Blocks.SAND) || (includeRed && state.is(Blocks.RED_SAND));
	}

	public static LevelChunk chunkAt(ClientLevel level, int blockX, int blockZ) {
		return level.getChunkSource().getChunk(blockX >> 4, blockZ >> 4, ChunkStatus.FULL, false);
	}

	public static boolean isLoaded(ClientLevel level, int blockX, int blockZ) {
		return chunkAt(level, blockX, blockZ) != null;
	}

	/** True when every block above {@code pos} up to the top of the world is air. */
	public static boolean isSkyExposed(ClientLevel level, BlockPos pos) {
		LevelChunk chunk = chunkAt(level, pos.getX(), pos.getZ());
		if (chunk == null) {
			return false;
		}
		int top = chunk.getHighestFilledSectionIndex();
		if (top < 0) {
			return true;
		}
		int topY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(top)) + 15;
		int lx = pos.getX() & 15;
		int lz = pos.getZ() & 15;
		int y = pos.getY() + 1;
		while (y <= topY) {
			int index = chunk.getSectionIndex(y);
			if (index < 0 || index >= chunk.getSections().length) {
				return true;
			}
			LevelChunkSection section = chunk.getSection(index);
			int sectionBase = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
			if (section.hasOnlyAir()) {
				y = sectionBase + 16;
				continue;
			}
			if (!section.getBlockState(lx, y - sectionBase, lz).isAir()) {
				return false;
			}
			y++;
		}
		return true;
	}

	public static boolean isDangerous(BlockState state) {
		return state.is(Blocks.CACTUS)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CAMPFIRE)
			|| state.is(Blocks.SOUL_CAMPFIRE)
			|| state.is(BlockTags.FIRE)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.COBWEB)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.WITHER_ROSE)
			|| state.is(Blocks.POINTED_DRIPSTONE)
			|| state.is(Blocks.LAVA);
	}

	/** A cell the player's body can occupy. */
	public static boolean isPassable(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || isDangerous(state)) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	/** A block whose top face can be stood on. */
	public static boolean isSolidFloor(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (isDangerous(state)) {
			return false;
		}
		return state.isFaceSturdy(level, pos, Direction.UP);
	}

	public static boolean isNearCactus(ClientLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					if (level.getBlockState(m.set(x + dx, y + dy, z + dz)).is(Blocks.CACTUS)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static boolean hasFluidNeighbor(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			if (!level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** The block cell containing the player's feet, adjusted for slightly-sunken or partial blocks. */
	public static BlockPos feetPos(ClientLevel level, LocalPlayer player) {
		BlockPos pos = BlockPos.containing(player.getX(), player.getY() + 0.2, player.getZ());
		if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
			return pos.above();
		}
		return pos;
	}

	/** Blocks directly under the player's hitbox (mining them would drop the player). */
	public static boolean isSupportBlock(LocalPlayer player, BlockPos pos) {
		AABB box = player.getBoundingBox();
		int y = (int) Math.floor(player.getY() - 0.2);
		if (pos.getY() != y && pos.getY() != y - 1) {
			return false;
		}
		int minX = (int) Math.floor(box.minX + 0.001);
		int maxX = (int) Math.floor(box.maxX - 0.001);
		int minZ = (int) Math.floor(box.minZ + 0.001);
		int maxZ = (int) Math.floor(box.maxZ - 0.001);
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}
}
