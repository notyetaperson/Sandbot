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
import com.nunkin.bot.world.ScanResult;
import com.nunkin.bot.world.WorldUtil;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Strip-mines sky-exposed sand around the player, walking between patches, picking up dropped items and
 * looting nearby containers.
 */
public final class MineTask {
	public enum Result {
		RUNNING, INVENTORY_FULL, DEPLETED, NO_TOOL
	}

	public enum Mode {
		SELECT("Choosing target"),
		MINE("Mining"),
		MOVE("Walking"),
		COLLECT("Collecting drops"),
		RAID("Raiding a container");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	private record Aim(BlockPos pos, Direction face, Vec3 point, float yaw, float pitch) {
	}

	private static final int COLLECT_BATCH = 5;
	private static final int COLLECT_MAX_AGE_TICKS = 200;

	private final SandBot bot;
	private final ChestRaider raider;
	private final PathExecutor executor = new PathExecutor();
	private final Long2LongOpenHashMap blacklist = new Long2LongOpenHashMap();
	private final Long2IntOpenHashMap breakCounts = new Long2IntOpenHashMap();
	private final Int2LongOpenHashMap itemBlacklist = new Int2LongOpenHashMap();
	private final RandomSource random = RandomSource.create();
	private Mode mode = Mode.SELECT;
	private Aim target;
	private int targetTicks;
	private boolean breaking;
	private int breakCooldown;
	private BlockPos moveTarget;
	private ItemEntity collectItem;
	private int collectWait;
	private int rescanTimer;
	private int idleTicks;
	private int minedThisCycle;
	private String detail = "";

	public MineTask(SandBot bot) {
		this.bot = bot;
		this.raider = new ChestRaider(bot);
	}

	public void begin() {
		halt();
		blacklist.clear();
		breakCounts.clear();
		itemBlacklist.clear();
		rescanTimer = 0;
		idleTicks = 0;
		detail = "Starting to mine";
	}

	public void resetCycleCounter() {
		minedThisCycle = 0;
	}

	/** Stops breaking and walking immediately (keeps blacklists). */
	public void halt() {
		if (breaking) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gameMode != null) {
				mc.gameMode.stopDestroyBlock();
			}
		}
		breaking = false;
		target = null;
		collectItem = null;
		executor.stop();
		raider.halt();
		mode = Mode.SELECT;
		InputController.clearKeys();
	}

	public ChestRaider raider() {
		return raider;
	}

	public Mode mode() {
		return mode;
	}

	public BlockPos target() {
		return target == null ? null : target.pos();
	}

	public BlockPos moveTarget() {
		return mode == Mode.MOVE ? moveTarget : null;
	}

	public Path path() {
		return mode == Mode.RAID ? raider.path() : executor.path();
	}

	public int pathIndex() {
		return mode == Mode.RAID ? raider.pathIndex() : executor.index();
	}

	public int minedThisCycle() {
		return minedThisCycle;
	}

	public String detail() {
		return detail;
	}

	public Result tick(Minecraft mc, LocalPlayer player, ClientLevel level) {
		BotConfig cfg = ConfigManager.get();
		long now = bot.ticks();
		if (now % 200 == 0) {
			purge(now);
		}

		if (InventoryUtil.isFull(player, cfg.includeRedSand)) {
			halt();
			detail = "Inventory full";
			return Result.INVENTORY_FULL;
		}

		if (mode == Mode.RAID) {
			if (raider.tick(mc, player, level, cfg) == ChestRaider.Status.DONE) {
				mode = Mode.SELECT;
			}
			detail = raider.detail();
			return Result.RUNNING;
		}
		if (InventoryUtil.hasForeignContainerOpen(player)) {
			player.closeContainer();
		}

		if (--rescanTimer <= 0) {
			bot.rescan();
			rescanTimer = 60;
		}

		int tool = InventoryUtil.findBestTool(player, cfg.minToolDurability);
		if (tool >= 0) {
			if (!InventoryUtil.select(mc, player, tool)) {
				detail = "Equipping shovel";
				return Result.RUNNING;
			}
		} else if (!cfg.allowHandMining) {
			halt();
			detail = "No usable shovel";
			return Result.NO_TOOL;
		} else if (player.getMainHandItem().isDamageableItem()) {
			int slot = safeHandSlot(player);
			if (slot >= 0) {
				player.getInventory().setSelectedSlot(slot);
			}
		}

		switch (mode) {
			case MINE -> tickMine(mc, player, level, cfg);
			case MOVE -> tickMove(player, level, cfg);
			case COLLECT -> tickCollect(player, level, cfg);
			default -> {
				return select(player, level, cfg);
			}
		}
		return Result.RUNNING;
	}

	private void tickMine(Minecraft mc, LocalPlayer player, ClientLevel level, BotConfig cfg) {
		BlockPos pos = target.pos();
		BlockState state = level.getBlockState(pos);
		if (!WorldUtil.isTargetSand(state, cfg.includeRedSand)) {
			if (breaking) {
				int previous = breakCounts.addTo(pos.asLong(), 1);
				if (previous + 1 >= 3) {
					BotLog.warn("Block at " + pos.toShortString() + " keeps coming back (protected?) — skipping it");
					blacklist(pos, 6000);
				}
				minedThisCycle++;
				bot.onSandMined();
			}
			breaking = false;
			target = null;
			mode = Mode.SELECT;
			breakCooldown = cfg.breakDelayTicks + (cfg.humanize ? random.nextInt(2) : 0);
			return;
		}

		Vec3 eye = player.getEyePosition();
		if (eye.distanceTo(target.point()) > cfg.reach + 0.75) {
			stopBreaking(mc);
			mode = Mode.SELECT;
			target = null;
			return;
		}

		bot.rotation().request(target.yaw(), target.pitch(), RotationController.PRIORITY_MINE);
		targetTicks++;
		detail = "Mining sand at " + pos.toShortString();
		boolean grounded = player.onGround() || player.isInWater();
		if (grounded && RotationController.isFacing(player, target.yaw(), target.pitch(), 3.5f) && mc.gameMode != null) {
			bot.setAttacking(true);
			if (mc.gameMode.continueDestroyBlock(pos, target.face())) {
				level.addBreakingBlockEffect(pos, target.face());
				player.swing(InteractionHand.MAIN_HAND);
			}
			breaking = true;
		}
		if (targetTicks > 200) {
			BotLog.debug("Gave up on block " + pos.toShortString());
			blacklist(pos, 1200);
			stopBreaking(mc);
			mode = Mode.SELECT;
			target = null;
		}
	}

	private void stopBreaking(Minecraft mc) {
		if (breaking && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		breaking = false;
	}

	private Result select(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		long now = bot.ticks();
		if (breakCooldown > 0) {
			breakCooldown--;
		}

		Aim aim = findReachable(player, level, cfg);
		if (cfg.collectRadius > 0 && player.onGround()) {
			List<ItemEntity> drops = findItems(player, level, cfg, now);
			int oldest = 0;
			for (ItemEntity drop : drops) {
				oldest = Math.max(oldest, drop.getAge());
			}
			boolean collectNow = aim == null || drops.size() >= COLLECT_BATCH || oldest > COLLECT_MAX_AGE_TICKS;
			if (collectNow && !drops.isEmpty() && startCollecting(player, level, drops.get(0), now)) {
				return Result.RUNNING;
			}
		}

		if (aim != null) {
			idleTicks = 0;
			bot.rotation().request(aim.yaw(), aim.pitch(), RotationController.PRIORITY_MINE);
			if (breakCooldown <= 0) {
				target = aim;
				targetTicks = 0;
				breaking = false;
				mode = Mode.MINE;
			}
			detail = "Targeting sand at " + aim.pos().toShortString();
			return Result.RUNNING;
		}

		if (cfg.raidChests && player.onGround() && InventoryUtil.freeSlots(player) > 0 && raider.begin(player, level, cfg)) {
			executor.stop();
			idleTicks = 0;
			mode = Mode.RAID;
			detail = raider.detail();
			return Result.RUNNING;
		}

		BlockPos next = nearestCandidate(player, level, cfg);
		if (next == null) {
			idleTicks++;
			detail = "No reachable sand left nearby";
			if (idleTicks == 1 || idleTicks % 30 == 0) {
				bot.rescan();
			}
			return idleTicks > 60 ? Result.DEPLETED : Result.RUNNING;
		}

		Path path = PathFinder.find(level, WorldUtil.feetPos(level, player), Goal.near(level, player, next, cfg.reach), 4000);
		if (path == null) {
			blacklistArea(next, 1200);
			detail = "No path to " + next.toShortString();
			return Result.RUNNING;
		}
		if (path.size() <= 1) {
			blacklist(next, 600);
			return Result.RUNNING;
		}
		idleTicks = 0;
		executor.follow(path);
		moveTarget = next;
		mode = Mode.MOVE;
		detail = "Walking to sand (" + (path.size() - 1) + " steps)";
		return Result.RUNNING;
	}

	private boolean startCollecting(LocalPlayer player, ClientLevel level, ItemEntity item, long now) {
		Path path = PathFinder.find(level, WorldUtil.feetPos(level, player), Goal.pickup(item.blockPosition()), 1500);
		if (path == null || !path.complete()) {
			itemBlacklist.put(item.getId(), now + 400);
			return false;
		}
		collectItem = item;
		collectWait = 0;
		mode = Mode.COLLECT;
		if (path.size() > 1) {
			executor.follow(path);
		} else {
			executor.stop();
		}
		detail = "Collecting dropped items";
		return true;
	}

	private void tickMove(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		if (moveTarget != null && !WorldUtil.isTargetSand(level.getBlockState(moveTarget), cfg.includeRedSand)) {
			executor.stop();
			mode = Mode.SELECT;
			return;
		}
		if ((bot.ticks() & 3) == 0 && player.onGround() && findReachable(player, level, cfg) != null) {
			executor.stop();
			InputController.clearKeys();
			mode = Mode.SELECT;
			return;
		}
		PathExecutor.Status status = executor.tick(player, level, bot.rotation(), cfg);
		switch (status) {
			case DONE, IDLE -> mode = Mode.SELECT;
			case STUCK, OFF_PATH -> {
				if (moveTarget != null) {
					blacklist(moveTarget, 400);
				}
				InputController.clearKeys();
				mode = Mode.SELECT;
			}
			default -> detail = "Walking to sand at " + (moveTarget == null ? "?" : moveTarget.toShortString());
		}
	}

	private void tickCollect(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		long now = bot.ticks();
		if (collectItem == null || collectItem.isRemoved()) {
			executor.stop();
			collectItem = null;
			mode = Mode.SELECT;
			return;
		}
		detail = "Collecting dropped items";
		if (executor.isActive()) {
			PathExecutor.Status status = executor.tick(player, level, bot.rotation(), cfg);
			if (status == PathExecutor.Status.STUCK || status == PathExecutor.Status.OFF_PATH) {
				itemBlacklist.put(collectItem.getId(), now + 600);
				collectItem = null;
				InputController.clearKeys();
				mode = Mode.SELECT;
			}
			return;
		}
		collectWait++;
		Vec3 itemPos = collectItem.position();
		double dx = itemPos.x - player.getX();
		double dz = itemPos.z - player.getZ();
		if (collectWait > 6 && Math.sqrt(dx * dx + dz * dz) > 0.5) {
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			bot.rotation().request(yaw, 30f, RotationController.PRIORITY_WALK);
			if (Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 40f) {
				InputController.forward = true;
			}
		}
		if (collectWait > 40) {
			itemBlacklist.put(collectItem.getId(), now + 600);
			collectItem = null;
			mode = Mode.SELECT;
		}
	}

	private Aim findReachable(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		Vec3 eye = player.getEyePosition();
		BlockPos feet = WorldUtil.feetPos(level, player);
		int r = (int) Math.ceil(cfg.reach);
		long now = bot.ticks();
		Aim best = null;
		double bestScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = -1; dy <= 3; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					if (!WorldUtil.isTargetSand(level.getBlockState(m), cfg.includeRedSand)) continue;
					if (isBlacklisted(m.asLong(), now)) continue;
					double dist = eye.distanceTo(Vec3.atCenterOf(m));
					if (dist > cfg.reach) continue;
					BlockPos pos = m.immutable();
					if (WorldUtil.isSupportBlock(player, pos)) continue;
					if (!WorldUtil.isSkyExposed(level, pos)) continue;
					if (WorldUtil.hasFluidNeighbor(level, pos)) continue;
					Aim aim = computeAim(level, player, eye, pos, cfg.reach);
					if (aim == null) continue;
					// Clear higher layers first so the ground stays walkable and drops stay reachable.
					double score = dist + RotationController.angleTo(player, aim.yaw(), aim.pitch()) / 40.0 - dy * 2.0;
					if (score < bestScore) {
						bestScore = score;
						best = aim;
					}
				}
			}
		}
		return best;
	}

	private static Aim computeAim(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, double reach) {
		double x = pos.getX();
		double y = pos.getY();
		double z = pos.getZ();
		List<Vec3> points = new ArrayList<>(3);
		if (eye.y > y + 1.0) {
			points.add(new Vec3(x + 0.5, y + 1.0, z + 0.5));
		}
		if (eye.x < x) {
			points.add(new Vec3(x, y + 0.5, z + 0.5));
		} else if (eye.x > x + 1) {
			points.add(new Vec3(x + 1, y + 0.5, z + 0.5));
		}
		if (eye.z < z) {
			points.add(new Vec3(x + 0.5, y + 0.5, z));
		} else if (eye.z > z + 1) {
			points.add(new Vec3(x + 0.5, y + 0.5, z + 1));
		}
		for (Vec3 point : points) {
			if (eye.distanceTo(point) > reach + 0.4) continue;
			Vec3 end = point.add(point.subtract(eye).normalize().scale(0.25));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
				return new Aim(pos, hit.getDirection(), point, RotationController.yawTo(eye, point), RotationController.pitchTo(eye, point));
			}
		}
		return null;
	}

	private BlockPos nearestCandidate(LocalPlayer player, ClientLevel level, BotConfig cfg) {
		ScanResult scan = bot.lastScan();
		if (scan == null || scan.dimension != level.dimension()) {
			return null;
		}
		long now = bot.ticks();
		double px = player.getX();
		double pz = player.getZ();
		int ground = WorldUtil.feetPos(level, player).getY() - 1;
		for (int attempt = 0; attempt < 8; attempt++) {
			long bestKey = Long.MIN_VALUE;
			double bestDist = Double.MAX_VALUE;
			for (long key : scan.exposed) {
				if (isBlacklisted(key, now)) continue;
				double dx = BlockPos.getX(key) + 0.5 - px;
				double dz = BlockPos.getZ(key) + 0.5 - pz;
				int dy = BlockPos.getY(key) - ground;
				// Digging below the current ground is a last resort; finishing a layer elsewhere comes first.
				double d = dx * dx + dz * dz + (dy < 0 ? -dy * 400.0 : dy * dy * 4.0);
				if (d < bestDist) {
					bestDist = d;
					bestKey = key;
				}
			}
			if (bestKey == Long.MIN_VALUE) {
				return null;
			}
			BlockPos pos = BlockPos.of(bestKey);
			if (WorldUtil.isTargetSand(level.getBlockState(pos), cfg.includeRedSand) && WorldUtil.isSkyExposed(level, pos)
				&& !WorldUtil.hasFluidNeighbor(level, pos)) {
				return pos;
			}
			blacklist(pos, 200);
		}
		return null;
	}

	/** Dropped items worth walking to (sand, or anything that fits when "pick up all items" is on), nearest first. */
	private List<ItemEntity> findItems(LocalPlayer player, ClientLevel level, BotConfig cfg, long now) {
		double r = cfg.collectRadius;
		AABB box = player.getBoundingBox().inflate(r, 3.0, r);
		Set<Item> unsellable = bot.unsellableItems();
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, e -> {
			if (!e.isAlive() || !e.onGround() || itemBlacklist.get(e.getId()) > now) return false;
			ItemStack stack = e.getItem();
			if (InventoryUtil.isSand(stack, cfg.includeRedSand)) return true;
			if (!cfg.pickupAllItems || !InventoryUtil.canFit(player, stack)) return false;
			// Items that get thrown away again would just be picked straight back up.
			return !(cfg.dropUnsellable && unsellable.contains(stack.getItem()) && !InventoryUtil.isProtected(stack, cfg));
		});
		items.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
		return items;
	}

	private static int safeHandSlot(LocalPlayer player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.isDamageableItem()) {
				return i;
			}
		}
		return -1;
	}

	private boolean isBlacklisted(long key, long now) {
		return blacklist.get(key) > now;
	}

	private void blacklist(BlockPos pos, int ticks) {
		blacklist.put(pos.asLong(), bot.ticks() + ticks);
	}

	private void blacklistArea(BlockPos pos, int ticks) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					blacklist(pos.offset(dx, dy, dz), ticks);
				}
			}
		}
	}

	private void purge(long now) {
		blacklist.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
		itemBlacklist.int2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
		raider.purge(now);
	}
}
