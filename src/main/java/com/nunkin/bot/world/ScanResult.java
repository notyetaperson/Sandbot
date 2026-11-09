package com.nunkin.bot.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Snapshot of the surface around the player: one entry per (x, z) column. */
public final class ScanResult {
	public static final byte UNLOADED = 0;
	public static final byte SAND = 1;
	public static final byte RED_SAND = 2;
	public static final byte WATER = 3;
	public static final byte LAVA = 4;
	public static final byte FOLIAGE = 5;
	public static final byte SNOW = 6;
	public static final byte OTHER = 7;
	public static final byte OUTSIDE = 8;

	private static long nextId = 1;

	public final long id = nextId++;
	public final long time;
	public final ResourceKey<Level> dimension;
	public final int minX;
	public final int minZ;
	public final int size;
	public final int radiusChunks;
	public final byte[] types;
	public final short[] heights;
	public final long[] exposed;
	public final int exposedCount;
	public final int sandCount;
	public final int redSandCount;
	public final int loadedChunks;
	public final int totalChunks;
	public final int minY;
	public final int maxY;

	ScanResult(long time, ResourceKey<Level> dimension, int minX, int minZ, int size, int radiusChunks, byte[] types, short[] heights,
			   long[] exposed, int sandCount, int redSandCount, int loadedChunks, int totalChunks, int minY, int maxY) {
		this.time = time;
		this.dimension = dimension;
		this.minX = minX;
		this.minZ = minZ;
		this.size = size;
		this.radiusChunks = radiusChunks;
		this.types = types;
		this.heights = heights;
		this.exposed = exposed;
		this.exposedCount = exposed.length;
		this.sandCount = sandCount;
		this.redSandCount = redSandCount;
		this.loadedChunks = loadedChunks;
		this.totalChunks = totalChunks;
		this.minY = minY;
		this.maxY = maxY;
	}

	public byte typeAt(int blockX, int blockZ) {
		int ix = blockX - minX;
		int iz = blockZ - minZ;
		if (ix < 0 || iz < 0 || ix >= size || iz >= size) {
			return UNLOADED;
		}
		return types[iz * size + ix];
	}

	public int heightAt(int blockX, int blockZ) {
		int ix = blockX - minX;
		int iz = blockZ - minZ;
		if (ix < 0 || iz < 0 || ix >= size || iz >= size) {
			return Integer.MIN_VALUE;
		}
		return heights[iz * size + ix];
	}

	public static String typeName(byte type) {
		return switch (type) {
			case SAND -> "Sand (sky-exposed)";
			case RED_SAND -> "Red sand (sky-exposed)";
			case WATER -> "Water";
			case LAVA -> "Lava";
			case FOLIAGE -> "Leaves / plants";
			case SNOW -> "Snow / ice";
			case OTHER -> "Other surface";
			case OUTSIDE -> "Outside scan radius";
			default -> "Not loaded";
		};
	}
}
