package com.nunkin.bot.task;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InputController;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.control.PathExecutor;
import com.nunkin.bot.control.RotationController;
import com.nunkin.bot.path.Goal;
import com.nunkin.bot.path.Path;
import com.nunkin.bot.path.PathFinder;
import com.nunkin.bot.world.WorldUtil;
import com.nunkin.config.BotConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Walks to nearby chests, barrels and shulker boxes, opens them and takes what's inside. */
public final class ChestRaider {
	public enum Status {
		RUNNING, DONE
	}

	private enum Stage {
		WALK, OPEN, LOOT
	}

	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int FULL_AFTER_REPEATS = 3;
	private static final long RETRY_LATER_TICKS = 1200;

	private final SandBot bot;
	private final PathExecutor executor = new PathExecutor();
	private final Long2LongOpenHashMap visited = new Long2LongOpenHashMap();
	private Stage stage = Stage.WALK;
	private BlockPos target;
	private String targetName = "";
	private int stageTicks;
	private int clickCooldown;
	private int lastSlot = -1;
	private int repeats;
	private int itemsTaken;
	private String detail = "";

	public ChestRaider(SandBot bot) {
		this.bot = bot;
	}

	public static boolean isRaidable(BlockState state) {
		Block block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
	}

	/** Raidable containers in loaded chunks inside the given block-coordinate rectangle. */
	public static List<BlockPos> containersIn(ClientLevel level, int minX, int minZ, int maxX, int maxZ) {
		List<BlockPos> found = new ArrayList<>();
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					BlockPos pos = entity.getBlockPos();
					if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
					if (isRaidable(entity.getBlockState())) {
						found.add(pos);
					}
				}
			}
		}
		return found;
	}

	public BlockPos target() {
		return target;
	}

	public Path path() {
		return executor.path();
	}

	public int pathIndex() {
		return executor.index();
	}

	public String detail() {
		return detail;
	}

	public boolean isVisited(BlockPos pos) {
		return visited.get(pos.asLong()) > bot.ticks();
	}

	public void halt() {
		executor.stop();
		target = null;
	}

	public void purge(long now) {
		visited.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
	}

	/** Picks the nearest container that hasn't been looted recently and heads for it. */
	public boolean begin(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		long now = bot.ticks();
		BlockPos feet = WorldUtil.feetPos(level, player);
		int r = cfg.raidRadius;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : containersIn(level, feet.getX() - r, feet.getZ() - r, feet.getX() + r, feet.getZ() + r)) {
			if (visited.get(pos.asLong()) > now || Math.abs(pos.getY() - feet.getY()) > r) continue;
			double distance = pos.distSqr(feet);
			if (distance <= (double) r * r && distance < bestDistance) {
				bestDistance = distance;
				best = pos;
			}
		}
		if (best == null) {
			return false;
		}
		if (hitFrom(level, player, best, cfg.reach) != null) {
			start(level, best, Stage.OPEN);
			return true;
		}
		Path path = PathFinder.find(level, feet, Goal.near(level, player, best, cfg.reach), 3000);
		if (path == null || !path.complete() || path.size() <= 1) {
			markVisited(best, now + RETRY_LATER_TICKS);
			return false;
		}
		start(level, best, Stage.WALK);
		executor.follow(path);
		detail = "Walking to the " + targetName + " at " + best.toShortString();
		return true;
	}

	private void start(ClientLevel level, BlockPos pos, Stage first) {
		target = pos;
		targetName = level.getBlockState(pos).getBlock().getName().getString().toLowerCase(Locale.ROOT);
		stage = first;
		stageTicks = 0;
		clickCooldown = 0;
		lastSlot = -1;
		repeats = 0;
		itemsTaken = 0;
	}

	private void setStage(Stage next) {
		stage = next;
		stageTicks = 0;
	}

	public Status tick(Minecraft mc, LocalPlayer player, ClientLevel level, BotConfig cfg) {
		if (target == null) {
			return Status.DONE;
		}
		stageTicks++;
		if (!isRaidable(level.getBlockState(target))) {
			finish(player);
			return Status.DONE;
		}
		switch (stage) {
			case WALK -> {
				if ((bot.ticks() & 3) == 0 && player.onGround() && hitFrom(level, player, target, cfg.reach) != null) {
					executor.stop();
					InputController.clearKeys();
					setStage(Stage.OPEN);
					break;
				}
				PathExecutor.Status status = executor.tick(player, level, bot.rotation(), cfg);
				if (status == PathExecutor.Status.DONE || status == PathExecutor.Status.IDLE) {
					InputController.clearKeys();
					setStage(Stage.OPEN);
				} else if (status == PathExecutor.Status.STUCK || status == PathExecutor.Status.OFF_PATH) {
					markVisited(target, bot.ticks() + RETRY_LATER_TICKS);
					finish(player);
					return Status.DONE;
				}
			}
			case OPEN -> {
				BlockHitResult hit = hitFrom(level, player, target, cfg.reach);
				if (hit == null) {
					if (stageTicks > 20) {
						BotLog.debug("Can't reach the " + targetName + " at " + target.toShortString());
						markVisited(target, bot.ticks() + RETRY_LATER_TICKS);
						finish(player);
						return Status.DONE;
					}
					break;
				}
				Vec3 eye = player.getEyePosition();
				float yaw = RotationController.yawTo(eye, hit.getLocation());
				float pitch = RotationController.pitchTo(eye, hit.getLocation());
				bot.rotation().request(yaw, pitch, RotationController.PRIORITY_ACTION);
				detail = "Opening the " + targetName + " at " + target.toShortString();
				if (player.onGround() && RotationController.isFacing(player, yaw, pitch, 5f) && mc.gameMode != null) {
					mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
					player.swing(InteractionHand.MAIN_HAND);
					setStage(Stage.LOOT);
				} else if (stageTicks > 60) {
					markVisited(target, bot.ticks() + RETRY_LATER_TICKS);
					finish(player);
					return Status.DONE;
				}
			}
			case LOOT -> {
				if (!InventoryUtil.hasForeignContainerOpen(player) || mc.gameMode == null) {
					if (stageTicks > OPEN_TIMEOUT_TICKS) {
						BotLog.info("The " + targetName + " at " + target.toShortString() + " didn't open (locked or protected), skipping it");
						markVisited(target, refillUntil(cfg));
						finish(player);
						return Status.DONE;
					}
					detail = "Waiting for the " + targetName + " to open";
					break;
				}
				if (stageTicks < 4 || clickCooldown-- > 0) {
					break;
				}
				AbstractContainerMenu menu = player.containerMenu;
				Slot next = nextSlot(menu, player, cfg);
				if (next != null && next.index == lastSlot && ++repeats >= FULL_AFTER_REPEATS) {
					BotLog.info("No room left, leaving the rest in the " + targetName);
					next = null;
				}
				if (next == null) {
					finishLooting(player, level, cfg);
					return Status.DONE;
				}
				if (next.index != lastSlot) {
					repeats = 0;
					itemsTaken += next.getItem().getCount();
				}
				mc.gameMode.handleInventoryMouseClick(menu.containerId, next.index, 0, ClickType.QUICK_MOVE, player);
				lastSlot = next.index;
				clickCooldown = cfg.sellClickDelayTicks;
				detail = "Looting the " + targetName + " (" + itemsTaken + " items)";
			}
		}
		return Status.RUNNING;
	}

	private Slot nextSlot(AbstractContainerMenu menu, LocalPlayer player, BotConfig cfg) {
		for (Slot slot : menu.slots) {
			if (InventoryUtil.isPlayerSlot(slot, player) || !slot.hasItem()) continue;
			ItemStack stack = slot.getItem();
			if (!cfg.raidFilter.isBlank() && !InventoryUtil.matchesKeywords(stack, cfg.raidFilter)) continue;
			if (cfg.dropUnsellable && bot.unsellableItems().contains(stack.getItem()) && !InventoryUtil.isProtected(stack, cfg)) continue;
			if (!InventoryUtil.canFit(player, stack)) continue;
			return slot;
		}
		return null;
	}

	private void finishLooting(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		long until = refillUntil(cfg);
		markVisited(target, until);
		// Both halves of a double chest open the same inventory.
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbour = target.relative(direction);
			if (level.getBlockState(neighbour).getBlock() instanceof ChestBlock) {
				markVisited(neighbour, until);
			}
		}
		bot.stats().addRaid(itemsTaken);
		if (itemsTaken > 0) {
			BotLog.success("Looted " + itemsTaken + " items from the " + targetName + " at " + target.toShortString());
		} else {
			BotLog.info("The " + targetName + " at " + target.toShortString() + " had nothing to take");
		}
		finish(player);
	}

	private void finish(LocalPlayer player) {
		if (InventoryUtil.hasForeignContainerOpen(player)) {
			player.closeContainer();
		}
		executor.stop();
		InputController.clearKeys();
		target = null;
	}

	private long refillUntil(BotConfig cfg) {
		return cfg.raidRefillMinutes <= 0 ? Long.MAX_VALUE : bot.ticks() + cfg.raidRefillMinutes * 1200L;
	}

	private void markVisited(BlockPos pos, long until) {
		visited.put(pos.asLong(), until);
	}

	/** A ray from the eyes that lands on the container within reach, or null. */
	private static BlockHitResult hitFrom(ClientLevel level, LocalPlayer player, BlockPos pos, double reach) {
		Vec3 eye = player.getEyePosition();
		double x = pos.getX();
		double y = pos.getY();
		double z = pos.getZ();
		List<Vec3> points = new ArrayList<>(4);
		points.add(Vec3.atCenterOf(pos));
		points.add(new Vec3(x + 0.5, y + 0.8, z + 0.5));
		points.add(new Vec3(eye.x < x ? x + 0.1 : x + 0.9, y + 0.5, z + 0.5));
		points.add(new Vec3(x + 0.5, y + 0.5, eye.z < z ? z + 0.1 : z + 0.9));
		for (Vec3 point : points) {
			if (eye.distanceTo(point) > reach + 0.6) continue;
			Vec3 end = point.add(point.subtract(eye).normalize().scale(0.3));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && eye.distanceTo(hit.getLocation()) <= reach) {
				return hit;
			}
		}
		return null;
	}
}
