package com.nunkin.ui;

import com.nunkin.bot.SandBot;
import com.nunkin.bot.path.Path;
import com.nunkin.bot.task.ChestRaider;
import com.nunkin.bot.task.MineTask;
import com.nunkin.bot.world.ScanResult;
import com.nunkin.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;

/** Top-down map of the last surface scan with live overlays (bot, path, target, drops, players). */
public final class RadarRenderer {
	public static final int CONTAINER_COLOR = 0xFFF97316;

	private static long cachedId = -1;
	private static int cachedSize = -1;
	private static int[] runs = new int[0];
	private static int runCount;

	private RadarRenderer() {
	}

	public static void render(GuiGraphics g, Font font, ScanResult scan, int x, int y, int size, int mouseX, int mouseY) {
		Theme.card(g, x - 2, y - 2, size + 4, size + 4, 0xFF08090C, Theme.LINE);
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (scan == null || player == null || mc.level == null || scan.dimension != mc.level.dimension()) {
			Theme.textCenter(g, font, "No scan yet", x + size / 2, y + size / 2 - 8, Theme.TEXT_DIM);
			Theme.textCenter(g, font, "Press Rescan or start the bot", x + size / 2, y + size / 2 + 4, Theme.TEXT_FAINT);
			return;
		}
		if (scan.id != cachedId || size != cachedSize) {
			rebuild(scan, size);
		}
		for (int i = 0; i < runCount; i++) {
			int o = i * 4;
			g.fill(x + runs[o + 1], y + runs[o], x + runs[o + 2], y + runs[o] + 1, runs[o + 3]);
		}

		float scale = size / (float) scan.size;
		int chunks = scan.size / 16;
		for (int c = 1; c < chunks; c++) {
			int p = (int) (c * 16 * scale);
			g.fill(x + p, y, x + p + 1, y + size, 0x12FFFFFF);
			g.fill(x, y + p, x + size, y + p + 1, 0x12FFFFFF);
		}

		g.enableScissor(x, y, x + size, y + size);
		SandBot bot = SandBot.get();
		MineTask task = bot.mineTask();

		Path path = task.path();
		if (path != null) {
			for (int i = Math.max(0, task.pathIndex() - 1); i < path.size(); i++) {
				BlockPos n = path.get(i);
				int px = x + (int) ((n.getX() + 0.5 - scan.minX) * scale);
				int pz = y + (int) ((n.getZ() + 0.5 - scan.minZ) * scale);
				g.fill(px - 1, pz - 1, px + 1, pz + 1, 0xFF22D3EE);
			}
		}

		List<String> playerNames = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof ItemEntity item) {
				int px = x + (int) ((item.getX() - scan.minX) * scale);
				int pz = y + (int) ((item.getZ() - scan.minZ) * scale);
				boolean sand = item.getItem().is(Items.SAND) || item.getItem().is(Items.RED_SAND);
				g.fill(px, pz, px + 1, pz + 1, sand ? 0xFFFDE047 : 0xFFFFFFFF);
			} else if (entity instanceof AbstractClientPlayer other && other != player) {
				int px = x + (int) ((other.getX() - scan.minX) * scale);
				int pz = y + (int) ((other.getZ() - scan.minZ) * scale);
				Theme.dot(g, px, pz, 0xFFEF4444);
				if (Math.abs(mouseX - px) <= 3 && Math.abs(mouseY - pz) <= 3) {
					playerNames.add(other.getGameProfile().name());
				}
			}
		}

		ChestRaider raider = task.raider();
		BlockPos raidTarget = raider.target();
		List<String> containerNames = new ArrayList<>();
		for (BlockPos chest : ChestRaider.containersIn(mc.level, scan.minX, scan.minZ, scan.minX + scan.size - 1, scan.minZ + scan.size - 1)) {
			int px = x + (int) ((chest.getX() + 0.5 - scan.minX) * scale);
			int pz = y + (int) ((chest.getZ() + 0.5 - scan.minZ) * scale);
			boolean looted = raider.isVisited(chest);
			int color = chest.equals(raidTarget) ? Theme.alpha(CONTAINER_COLOR, 0.45f + 0.55f * Theme.pulse(500))
				: looted ? 0xFF6B7280 : CONTAINER_COLOR;
			g.fill(px - 1, pz - 1, px + 2, pz + 2, color);
			if (Math.abs(mouseX - px) <= 2 && Math.abs(mouseY - pz) <= 2) {
				String name = mc.level.getBlockState(chest).getBlock().getName().getString();
				containerNames.add(name + (looted ? " (looted)" : "") + " at " + chest.toShortString());
			}
		}

		BlockPos target = task.target() != null ? task.target() : task.moveTarget();
		if (target != null) {
			int px = x + (int) ((target.getX() - scan.minX) * scale);
			int pz = y + (int) ((target.getZ() - scan.minZ) * scale);
			int s = Math.max(2, (int) scale + 2);
			int color = Theme.alpha(0xFFF43F5E, 0.6f + 0.4f * Theme.pulse(600));
			Theme.roundOutline(g, px - 1, pz - 1, s, s, color);
		}

		int ppx = x + (int) ((player.getX() - scan.minX) * scale);
		int ppz = y + (int) ((player.getZ() - scan.minZ) * scale);
		g.pose().pushMatrix();
		g.pose().translate(ppx + 0.5f, ppz + 0.5f);
		g.pose().rotate((float) Math.toRadians(player.getYRot() + 180f));
		int arrow = 0xFFFFFFFF;
		g.fill(0, -5, 1, -4, arrow);
		g.fill(-1, -4, 2, -3, arrow);
		g.fill(-2, -3, 3, -2, arrow);
		g.fill(-3, -2, 4, -1, arrow);
		g.fill(-1, -1, 2, 3, arrow);
		g.pose().popMatrix();
		g.disableScissor();

		if (mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size) {
			int bx = scan.minX + (int) ((mouseX - x) / scale);
			int bz = scan.minZ + (int) ((mouseY - y) / scale);
			byte type = scan.typeAt(bx, bz);
			int height = scan.heightAt(bx, bz);
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(ScanResult.typeName(type)).withColor(colorFor(type, 1f)));
			lines.add(Component.literal("X " + bx + "  Z " + bz + (type == ScanResult.UNLOADED ? "" : "  Y " + height)).withColor(Theme.TEXT_DIM));
			for (String name : playerNames) {
				lines.add(Component.literal("Player: " + name).withColor(0xFFEF4444));
			}
			for (String name : containerNames) {
				lines.add(Component.literal(name).withColor(CONTAINER_COLOR));
			}
			g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	public static int colorFor(byte type, float shade) {
		int base = switch (type) {
			case ScanResult.SAND -> 0xFFE8D28E;
			case ScanResult.RED_SAND -> ConfigManager.get().includeRedSand ? 0xFFD9843F : 0xFF8A5A36;
			case ScanResult.WATER -> 0xFF2A5B9A;
			case ScanResult.LAVA -> 0xFFE0662A;
			case ScanResult.FOLIAGE -> 0xFF2E5A2B;
			case ScanResult.SNOW -> 0xFFD7E1EA;
			case ScanResult.OTHER -> 0xFF4A505D;
			case ScanResult.OUTSIDE -> 0xFF08090C;
			default -> 0xFF181C24;
		};
		return type == ScanResult.UNLOADED || type == ScanResult.OUTSIDE ? base : Theme.shade(base, shade);
	}

	private static void rebuild(ScanResult scan, int size) {
		cachedId = scan.id;
		cachedSize = size;
		int needed = size * size * 4;
		if (runs.length < Math.min(needed, 400_000)) {
			runs = new int[Math.min(needed, 400_000)];
		}
		runCount = 0;
		float range = Math.max(1, scan.maxY - scan.minY);
		for (int py = 0; py < size; py++) {
			int bz = Math.min(scan.size - 1, py * scan.size / size);
			int runStart = 0;
			int runColor = 0;
			for (int px = 0; px <= size; px++) {
				int color;
				if (px < size) {
					int bx = Math.min(scan.size - 1, px * scan.size / size);
					int index = bz * scan.size + bx;
					byte type = scan.types[index];
					float h = Mth.clamp((scan.heights[index] - scan.minY) / range, 0f, 1f);
					color = colorFor(type, 0.72f + 0.38f * h);
				} else {
					color = ~runColor;
				}
				if (px == 0) {
					runColor = color;
					continue;
				}
				if (color != runColor) {
					if ((runCount + 1) * 4 > runs.length) {
						return;
					}
					int o = runCount * 4;
					runs[o] = py;
					runs[o + 1] = runStart;
					runs[o + 2] = px;
					runs[o + 3] = runColor;
					runCount++;
					runStart = px;
					runColor = color;
				}
			}
		}
	}
}
