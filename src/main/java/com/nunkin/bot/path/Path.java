package com.nunkin.bot.path;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Sequence of feet positions from the start to the goal.
 *
 * @param complete false when the search ran out of budget and this only leads toward the goal
 */
public record Path(List<BlockPos> nodes, boolean complete, int expanded) {
	public int size() {
		return nodes.size();
	}

	public BlockPos get(int index) {
		return nodes.get(index);
	}

	public BlockPos last() {
		return nodes.get(nodes.size() - 1);
	}
}
