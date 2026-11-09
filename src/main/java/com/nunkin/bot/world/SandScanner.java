package com.nunkin.bot.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Finds sand whose column has nothing but air above it, in every chunk within a circular chunk radius of the player.
 */
public final class SandScanner {
	private SandScanner() {
	}

	public static boolean inRadius(int dx, int dz, int radius) {
		return dx * dx + dz * dz <= radius * radius;
	}

	public static int chunkCount(int radius) {
		int count = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (inRadius(dx, dz, radius)) count++;
			}
		}
		return count;
	}

	public static int countLoadedChunks(ClientLevel level, ChunkPos center, int radius) {
		int loaded = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (inRadius(dx, dz, radius) && level.getChunkSource().getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false) != null) {
					loaded++;
				}
			}
		}
		return loaded;
	}

	public static ScanResult scan(ClientLevel level, ChunkPos center, int radius, boolean includeRedSand) {
		int sizeChunks = radius * 2 + 1;
		int size = sizeChunks * 16;
		int minX = (center.x - radius) * 16;
		int minZ = (center.z - radius) * 16;
		byte[] types = new byte[size * size];
		short[] heights = new short[size * size];
		LongArrayList exposed = new LongArrayList();
		int sand = 0;
		int redSand = 0;
		int loaded = 0;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;

		for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
			for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
				if (!inRadius(cx - center.x, cz - center.z, radius)) {
					fillChunk(types, size, cx * 16 - minX, cz * 16 - minZ, ScanResult.OUTSIDE);
					continue;
				}
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				loaded++;
				LevelChunkSection[] sections = chunk.getSections();
				int top = chunk.getHighestFilledSectionIndex();
				int baseX = cx * 16;
				int baseZ = cz * 16;

				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						BlockState found = null;
						int foundY = 0;
						search:
						for (int si = top; si >= 0; si--) {
							LevelChunkSection section = sections[si];
							if (section.hasOnlyAir()) {
								continue;
							}
							int sectionBase = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
							for (int ly = 15; ly >= 0; ly--) {
								BlockState state = section.getBlockState(lx, ly, lz);
								if (!state.isAir()) {
									found = state;
									foundY = sectionBase + ly;
									break search;
								}
							}
						}

						int index = (baseZ + lz - minZ) * size + (baseX + lx - minX);
						if (found == null) {
							types[index] = ScanResult.OTHER;
							heights[index] = (short) level.getMinY();
							continue;
						}
						heights[index] = (short) foundY;
						minY = Math.min(minY, foundY);
						maxY = Math.max(maxY, foundY);
						byte type = classify(found);
						types[index] = type;
						if (type == ScanResult.SAND || (type == ScanResult.RED_SAND && includeRedSand)) {
							exposed.add(BlockPos.asLong(baseX + lx, foundY, baseZ + lz));
						}
						if (type == ScanResult.SAND) sand++;
						if (type == ScanResult.RED_SAND) redSand++;
					}
				}
			}
		}

		if (minY > maxY) {
			minY = maxY = 64;
		}
		return new ScanResult(Util.getMillis(), level.dimension(), minX, minZ, size, radius, types, heights,
			exposed.toLongArray(), sand, redSand, loaded, chunkCount(radius), minY, maxY);
	}

	private static void fillChunk(byte[] types, int size, int offsetX, int offsetZ, byte type) {
		for (int lz = 0; lz < 16; lz++) {
			int row = (offsetZ + lz) * size + offsetX;
			for (int lx = 0; lx < 16; lx++) {
				types[row + lx] = type;
			}
		}
	}

	private static byte classify(BlockState state) {
		if (state.is(Blocks.SAND)) return ScanResult.SAND;
		if (state.is(Blocks.RED_SAND)) return ScanResult.RED_SAND;
		if (!state.getFluidState().isEmpty()) {
			return state.getFluidState().is(FluidTags.LAVA) ? ScanResult.LAVA : ScanResult.WATER;
		}
		if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.DEAD_BUSH) || state.is(Blocks.CACTUS) || state.is(Blocks.FERN)) {
			return ScanResult.FOLIAGE;
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(BlockTags.ICE)) {
			return ScanResult.SNOW;
		}
		return ScanResult.OTHER;
	}
}
