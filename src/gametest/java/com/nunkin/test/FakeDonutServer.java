package com.nunkin.test;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Imitates DonutSMP's economy commands inside the test world: /rtp opens a destination menu, /sell opens a chest
 * that sells its sand when closed, /bal prints the balance and /pay transfers money.
 */
public class FakeDonutServer implements ModInitializer {
	/** What /sell buys; anything else is handed back. */
	public static final Map<Item, Double> PRICES = Map.of(Items.SAND, 5.0, Items.DIAMOND, 100.0, Items.GOLD_INGOT, 20.0, Items.EMERALD, 50.0);
	public static volatile boolean payeeOnline = true;
	public static double balance = 250.0;
	public static double paidTotal;
	public static int rtpMenusOpened;
	public static int sellMenusOpened;
	public static int itemsSold;
	public static final Map<Item, Integer> soldByItem = new ConcurrentHashMap<>();
	public static final Set<Item> offeredUnsellable = ConcurrentHashMap.newKeySet();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("rtp").executes(ctx -> {
				openRtpMenu(ctx.getSource().getPlayerOrException());
				return 1;
			}));
			dispatcher.register(Commands.literal("sell").executes(ctx -> {
				openSellMenu(ctx.getSource().getPlayerOrException());
				return 1;
			}));
			dispatcher.register(Commands.literal("bal").executes(ctx -> {
				ctx.getSource().getPlayerOrException().sendSystemMessage(
					Component.literal("Your balance is $" + String.format(Locale.ROOT, "%,.2f", balance)));
				return 1;
			}));
			dispatcher.register(Commands.literal("pay")
				.then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("amount", StringArgumentType.word()).executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						String target = StringArgumentType.getString(ctx, "player");
						double amount;
						try {
							amount = Double.parseDouble(StringArgumentType.getString(ctx, "amount").replace(",", ""));
						} catch (NumberFormatException e) {
							player.sendSystemMessage(Component.literal("Invalid amount."));
							return 0;
						}
						if (!payeeOnline) {
							player.sendSystemMessage(Component.literal("Player " + target + " is not online!"));
							return 0;
						}
						if (amount > balance) {
							player.sendSystemMessage(Component.literal("You don't have enough money!"));
							return 0;
						}
						balance -= amount;
						paidTotal += amount;
						player.sendSystemMessage(Component.literal("You paid " + target + " $" + String.format(Locale.ROOT, "%,.0f", amount) + "."));
						return 1;
					}))));
		});
	}

	private static ItemStack named(Item item, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	private static void openRtpMenu(ServerPlayer player) {
		rtpMenusOpened++;
		SimpleContainer container = new SimpleContainer(9);
		container.setItem(2, named(Items.GRASS_BLOCK, "\u1d0f\u1d20\u1d07\u0280\u1d21\u1d0f\u0280\u029f\u1d05"));
		container.setItem(4, named(Items.NETHERRACK, "Nether"));
		container.setItem(6, named(Items.END_STONE, "The End"));
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ChestMenu(MenuType.GENERIC_9x1, id, inventory, container, 1) {
			@Override
			public void clicked(int slot, int button, ClickType clickType, Player clicker) {
				if (slot == 2 && clicker instanceof ServerPlayer serverPlayer) {
					serverPlayer.closeContainer();
					serverPlayer.sendSystemMessage(Component.literal("Teleporting in 0 seconds, don't move!"));
					serverPlayer.teleportTo(0.5, -57, 0.5);
				}
			}

			@Override
			public ItemStack quickMoveStack(Player clicker, int index) {
				return ItemStack.EMPTY;
			}
		}, Component.literal("Random Teleport")));
	}

	private static void openSellMenu(ServerPlayer player) {
		sellMenusOpened++;
		SimpleContainer container = new SimpleContainer(54);
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, inventory, container, 6) {
			@Override
			public void removed(Player closer) {
				super.removed(closer);
				int sold = 0;
				double money = 0;
				for (int i = 0; i < container.getContainerSize(); i++) {
					ItemStack stack = container.getItem(i);
					Double price = PRICES.get(stack.getItem());
					if (price != null) {
						sold += stack.getCount();
						money += stack.getCount() * price;
						soldByItem.merge(stack.getItem(), stack.getCount(), Integer::sum);
					} else if (!stack.isEmpty()) {
						offeredUnsellable.add(stack.getItem());
						closer.getInventory().placeItemBackInInventory(stack.copy());
					}
					container.setItem(i, ItemStack.EMPTY);
				}
				if (sold > 0 && closer instanceof ServerPlayer serverPlayer) {
					balance += money;
					itemsSold += sold;
					serverPlayer.sendSystemMessage(Component.literal("You sold " + sold + " items for $" + String.format(Locale.ROOT, "%,.2f", money)));
				}
			}
		}, Component.literal("Sell Items")));
	}
}
