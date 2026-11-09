package com.nunkin.bot.task;

import com.nunkin.bot.BotLog;
import com.nunkin.bot.Notifications;
import com.nunkin.bot.SandBot;
import com.nunkin.bot.control.InventoryUtil;
import com.nunkin.bot.control.RotationController;
import com.nunkin.config.BotConfig;
import com.nunkin.config.ConfigManager;
import com.nunkin.ui.Theme;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Keeps the bot alive: eats when hungry, swats hostile mobs, and reacts to low health or nearby players. */
public final class SurvivalController {
	private final SandBot bot;
	private boolean eating;
	private int eatTicks;
	private int eatStartFood;
	private int previousSlot = -1;
	private boolean warnedNoFood;
	private boolean fighting;
	private boolean lowHealthTriggered;
	private long lastPlayerAlert;
	private String lastPlayerName;
	private boolean pausedForPlayer;
	private String status = "";

	public SurvivalController(SandBot bot) {
		this.bot = bot;
	}

	public boolean isEating() {
		return eating;
	}

	public boolean isActive() {
		return eating || fighting;
	}

	public String status() {
		return status;
	}

	public void reset() {
		eating = false;
		fighting = false;
		previousSlot = -1;
		pausedForPlayer = false;
		lastPlayerName = null;
		lowHealthTriggered = false;
	}

	public void checkDangers(LocalPlayer player, ClientLevel level) {
		BotConfig cfg = ConfigManager.get();
		float health = player.getHealth();
		if (health <= cfg.lowHealth) {
			if (!lowHealthTriggered) {
				lowHealthTriggered = true;
				bot.handleDanger(cfg.lowHealthAction, String.format(Locale.ROOT, "Low health (%.1f hearts)", health / 2f));
			}
		} else if (health >= Math.min(20f, cfg.lowHealth + 4f)) {
			lowHealthTriggered = false;
		}

		if (cfg.playerAlertRadius <= 0 || cfg.playerNearbyAction == BotConfig.DangerAction.IGNORE) {
			return;
		}
		List<String> ignored = cfg.ignoredPlayerList();
		AbstractClientPlayer nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (AbstractClientPlayer other : level.players()) {
			if (other == player || other.isSpectator()) continue;
			if (ignored.contains(other.getGameProfile().name().toLowerCase(Locale.ROOT))) continue;
			double d = other.distanceTo(player);
			if (d <= cfg.playerAlertRadius && d < nearestDistance) {
				nearest = other;
				nearestDistance = d;
			}
		}
		long now = Util.getMillis();
		if (nearest != null) {
			String name = nearest.getGameProfile().name();
			boolean fresh = !name.equals(lastPlayerName) || now - lastPlayerAlert > 60_000;
			if (fresh && !pausedForPlayer) {
				lastPlayerAlert = now;
				lastPlayerName = name;
				if (cfg.playerNearbyAction == BotConfig.DangerAction.PAUSE) {
					pausedForPlayer = true;
				}
				bot.handleDanger(cfg.playerNearbyAction, "Player " + name + " is " + (int) nearestDistance + "m away");
			}
		} else if (pausedForPlayer) {
			pausedForPlayer = false;
			lastPlayerName = null;
			if (bot.isPaused()) {
				BotLog.info("Nearby player left — resuming");
				bot.setPaused(false, null);
			}
		}
	}

	/** Returns true when survival needs control of the player this tick. */
	public boolean tickActions(Minecraft mc, LocalPlayer player, ClientLevel level) {
		BotConfig cfg = ConfigManager.get();
		fighting = false;
		if (eating) {
			tickEating(mc, player);
			return true;
		}
		if (cfg.defendMobs) {
			Mob threat = findThreat(player, level);
			if (threat != null) {
				fight(mc, player, threat);
				return true;
			}
		}
		if (cfg.autoEat && player.getFoodData().getFoodLevel() <= cfg.eatBelowHunger) {
			return startEating(mc, player);
		}
		return false;
	}

	private Mob findThreat(LocalPlayer player, ClientLevel level) {
		AABB box = player.getBoundingBox().inflate(4.0, 2.0, 4.0);
		Vec3 eye = player.getEyePosition();
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
			m -> m.isAlive() && m instanceof Enemy && !(m instanceof NeutralMob) && player.hasLineOfSight(m));
		Mob best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Mob mob : mobs) {
			double d = mob.getBoundingBox().distanceToSqr(eye);
			if (d < bestDistance) {
				bestDistance = d;
				best = mob;
			}
		}
		return best;
	}

	private void fight(Minecraft mc, LocalPlayer player, Mob mob) {
		fighting = true;
		bot.interruptForSurvival();
		Vec3 eye = player.getEyePosition();
		Vec3 aim = mob.getBoundingBox().getCenter();
		float yaw = RotationController.yawTo(eye, aim);
		float pitch = RotationController.pitchTo(eye, aim);
		bot.rotation().request(yaw, pitch, RotationController.PRIORITY_COMBAT);
		status = "Defending against " + mob.getName().getString();
		boolean inRange = mob.getBoundingBox().distanceToSqr(eye) <= 9.0;
		if (inRange && RotationController.isFacing(player, yaw, pitch, 25f) && player.getAttackStrengthScale(0.5f) >= 0.95f && mc.gameMode != null) {
			mc.gameMode.attack(player, mob);
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private boolean startEating(Minecraft mc, LocalPlayer player) {
		int slot = InventoryUtil.findFood(player);
		if (slot < 0) {
			if (!warnedNoFood) {
				warnedNoFood = true;
				BotLog.warn("Hungry but there is no food in the inventory");
				Notifications.push("No food", "Put food in the bot's inventory", Theme.WARN);
			}
			return false;
		}
		warnedNoFood = false;
		bot.interruptForSurvival();
		if (previousSlot < 0) {
			previousSlot = player.getInventory().getSelectedSlot();
		}
		if (!InventoryUtil.select(mc, player, slot)) {
			status = "Grabbing food";
			return true;
		}
		eating = true;
		eatTicks = 0;
		eatStartFood = player.getFoodData().getFoodLevel();
		status = "Eating " + player.getMainHandItem().getHoverName().getString();
		BotLog.info(status);
		return true;
	}

	private void tickEating(Minecraft mc, LocalPlayer player) {
		eatTicks++;
		status = "Eating";
		if (!player.isUsingItem()) {
			boolean ate = player.getFoodData().getFoodLevel() > eatStartFood;
			if ((eatTicks > 5 && ate) || !InventoryUtil.isGoodFood(player.getMainHandItem())) {
				finishEating(player);
				return;
			}
			if (eatTicks % 5 == 1 && mc.gameMode != null) {
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			}
		}
		if (eatTicks > 120) {
			finishEating(player);
		}
	}

	private void finishEating(LocalPlayer player) {
		eating = false;
		if (previousSlot >= 0) {
			player.getInventory().setSelectedSlot(previousSlot);
		}
		previousSlot = -1;
	}
}
