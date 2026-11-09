package com.nunkin.bot.path;

import com.nunkin.bot.world.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * A* over standing positions. Supports walking (incl. diagonals without corner cutting),
 * 1-block step-ups and drops of up to 3 blocks. Never breaks or places blocks.
 */
public final class PathFinder {
	private static final int[][] DIRECTIONS = {
		{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};
	private static final int MAX_DROP = 3;

	private final ClientLevel level;
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final Long2ByteOpenHashMap passableCache = new Long2ByteOpenHashMap();
	private final Long2ByteOpenHashMap walkableCache = new Long2ByteOpenHashMap();

	private static final class Node {
		final int x;
		final int y;
		final int z;
		double g = Double.MAX_VALUE;
		double h;
		Node parent;
		boolean closed;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private record Entry(Node node, double f) {
	}

	private PathFinder(ClientLevel level) {
		this.level = level;
	}

	public static Path find(ClientLevel level, BlockPos start, Goal goal, int maxNodes) {
		return new PathFinder(level).search(start, goal, maxNodes);
	}

	private Path search(BlockPos startPos, Goal goal, int maxNodes) {
		Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

		Node start = new Node(startPos.getX(), startPos.getY(), startPos.getZ());
		start.g = 0;
		start.h = goal.heuristic(start.x, start.y, start.z);
		nodes.put(BlockPos.asLong(start.x, start.y, start.z), start);
		open.add(new Entry(start, start.h));

		Node best = start;
		int expanded = 0;
		List<int[]> neighbors = new ArrayList<>(16);

		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll().node();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			expanded++;

			if (goal.isEnd(current.x, current.y, current.z)) {
				return build(current, true, expanded);
			}
			if (current.h < best.h) {
				best = current;
			}

			neighbors.clear();
			collectNeighbors(current, neighbors);
			for (int[] n : neighbors) {
				long key = BlockPos.asLong(n[0], n[1], n[2]);
				double g = current.g + n[3] / 100.0;
				Node next = nodes.get(key);
				if (next == null) {
					next = new Node(n[0], n[1], n[2]);
					next.h = goal.heuristic(next.x, next.y, next.z);
					nodes.put(key, next);
				} else if (next.closed || g >= next.g) {
					continue;
				}
				next.g = g;
				next.parent = current;
				open.add(new Entry(next, g + next.h * 1.15));
			}
		}

		if (best != start && best.h < start.h - 1.5) {
			return build(best, false, expanded);
		}
		return null;
	}

	private void collectNeighbors(Node node, List<int[]> out) {
		int x = node.x;
		int y = node.y;
		int z = node.z;
		for (int[] d : DIRECTIONS) {
			int dx = d[0];
			int dz = d[1];
			boolean diagonal = dx != 0 && dz != 0;
			int nx = x + dx;
			int nz = z + dz;
			int baseCost = diagonal ? 141 : 100;

			if (diagonal && (!bodyClear(x + dx, y, z) || !bodyClear(x, y, z + dz))) {
				continue;
			}

			if (walkable(nx, y, nz)) {
				out.add(new int[]{nx, y, nz, baseCost});
				continue;
			}

			if (!diagonal && walkable(nx, y + 1, nz) && passable(x, y + 2, z)) {
				out.add(new int[]{nx, y + 1, nz, baseCost + 110});
				continue;
			}

			if (bodyClear(nx, y, nz)) {
				for (int drop = 1; drop <= MAX_DROP; drop++) {
					int fy = y - drop;
					if (walkable(nx, fy, nz)) {
						// Drops of 2+ blocks can't be climbed back up, so only take them when it really pays off.
						int dropCost = drop == 1 ? 60 : drop == 2 ? 450 : 900;
						out.add(new int[]{nx, fy, nz, baseCost + dropCost});
						break;
					}
					if (!passable(nx, fy, nz)) {
						break;
					}
				}
			}
		}
	}

	private boolean bodyClear(int x, int y, int z) {
		return passable(x, y, z) && passable(x, y + 1, z);
	}

	private boolean passable(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		byte cached = passableCache.get(key);
		if (cached != 0) {
			return cached == 1;
		}
		boolean result = WorldUtil.isLoaded(level, x, z) && WorldUtil.isPassable(level, cursor.set(x, y, z));
		passableCache.put(key, result ? (byte) 1 : (byte) 2);
		return result;
	}

	private boolean walkable(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		byte cached = walkableCache.get(key);
		if (cached != 0) {
			return cached == 1;
		}
		boolean result = bodyClear(x, y, z)
			&& WorldUtil.isSolidFloor(level, cursor.set(x, y - 1, z))
			&& !WorldUtil.isNearCactus(level, cursor, x, y, z);
		walkableCache.put(key, result ? (byte) 1 : (byte) 2);
		return result;
	}

	private static Path build(Node end, boolean complete, int expanded) {
		List<BlockPos> nodes = new ArrayList<>();
		for (Node n = end; n != null; n = n.parent) {
			nodes.add(new BlockPos(n.x, n.y, n.z));
		}
		Collections.reverse(nodes);
		return new Path(nodes, complete, expanded);
	}
}
