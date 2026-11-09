package com.nunkin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BotConfig {
	public enum SellMode {
		GUI("GUI menu"),
		COMMAND("Command only");

		public final String label;

		SellMode(String label) {
			this.label = label;
		}
	}

	public enum DangerAction {
		IGNORE("Ignore"),
		WARN("Warn me"),
		PAUSE("Pause bot"),
		RTP("RTP away"),
		STOP("Stop bot"),
		DISCONNECT("Disconnect");

		public final String label;

		DangerAction(String label) {
			this.label = label;
		}
	}

	public enum HudCorner {
		TOP_LEFT("Top left"),
		TOP_RIGHT("Top right"),
		BOTTOM_RIGHT("Bottom right");

		public final String label;

		HudCorner(String label) {
			this.label = label;
		}
	}

	// Cycle & search
	public int cycleSeconds = 300;
	public int sandThreshold = 800;
	public int scanRadiusChunks = 5;
	public boolean rtpEveryCycle = true;
	public boolean rtpWhenDepleted = true;
	public boolean includeRedSand = false;

	// Teleport
	public String rtpCommand = "rtp";
	public String rtpMenuKeyword = "overworld";
	public int rtpTimeoutSeconds = 20;
	public int rtpDelaySeconds = 5;
	public int chunkWaitSeconds = 8;

	// Mining
	public double reach = 4.2;
	public int breakDelayTicks = 2;
	public int rotationSpeed = 40;
	public boolean sprint = true;
	public int collectRadius = 6;
	public int minToolDurability = 8;
	public boolean allowHandMining = true;
	public boolean sellWhenFull = true;
	public boolean humanize = true;

	// Looting
	public boolean pickupAllItems = true;
	public boolean raidChests = true;
	public int raidRadius = 8;
	public String raidFilter = "";
	public int raidRefillMinutes = 10;
	public boolean sellLoot = true;
	public String keepItems = "shulker, totem, elytra";
	public boolean dropUnsellable = false;

	// Selling
	public SellMode sellMode = SellMode.GUI;
	public String sellCommand = "sell";
	public String sellConfirmKeyword = "";
	public int sellClickDelayTicks = 2;

	// Payout
	public boolean payEnabled = true;
	public String payTarget = "";
	public String balanceCommand = "bal";
	public String payCommand = "pay {player} {amount}";
	public double keepBalance = 0;
	public double minPayout = 1;
	public String payConfirmKeyword = "confirm";
	public int payRetryMinutes = 5;

	// Safety
	public boolean autoEat = true;
	public int eatBelowHunger = 14;
	public boolean defendMobs = true;
	public int lowHealth = 8;
	public DangerAction lowHealthAction = DangerAction.STOP;
	public int playerAlertRadius = 24;
	public DangerAction playerNearbyAction = DangerAction.WARN;
	public String ignoredPlayers = "";
	public boolean autoReconnect = false;
	public int reconnectDelaySeconds = 15;
	public boolean keepRunningUnfocused = true;

	// Automation
	public boolean autoRespawn = false;
	public boolean remoteControl = false;

	// Interface
	public boolean showHud = true;
	public HudCorner hudCorner = HudCorner.TOP_LEFT;
	public double hudScale = 1.0;
	public int accent = 0;
	public boolean chatFeedback = true;
	public boolean toasts = true;

	public List<String> ignoredPlayerList() {
		List<String> out = new ArrayList<>();
		for (String part : ignoredPlayers.split("[,\\s]+")) {
			if (!part.isBlank()) {
				out.add(part.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (!payTarget.isBlank()) {
			out.add(payTarget.trim().toLowerCase(Locale.ROOT));
		}
		return out;
	}

	public void sanitize() {
		BotConfig d = new BotConfig();
		cycleSeconds = clamp(cycleSeconds, 30, 3600);
		sandThreshold = clamp(sandThreshold, 1, 30000);
		scanRadiusChunks = clamp(scanRadiusChunks, 1, 12);
		rtpTimeoutSeconds = clamp(rtpTimeoutSeconds, 5, 120);
		rtpDelaySeconds = clamp(rtpDelaySeconds, 0, 300);
		chunkWaitSeconds = clamp(chunkWaitSeconds, 1, 60);
		reach = Math.max(2.0, Math.min(4.5, reach));
		breakDelayTicks = clamp(breakDelayTicks, 0, 40);
		rotationSpeed = clamp(rotationSpeed, 5, 180);
		collectRadius = clamp(collectRadius, 0, 16);
		minToolDurability = clamp(minToolDurability, 0, 500);
		sellClickDelayTicks = clamp(sellClickDelayTicks, 0, 20);
		raidRadius = clamp(raidRadius, 2, 24);
		raidRefillMinutes = clamp(raidRefillMinutes, 0, 240);
		keepBalance = Math.max(0, keepBalance);
		minPayout = Math.max(0, minPayout);
		payRetryMinutes = clamp(payRetryMinutes, 1, 120);
		eatBelowHunger = clamp(eatBelowHunger, 1, 19);
		lowHealth = clamp(lowHealth, 1, 19);
		playerAlertRadius = clamp(playerAlertRadius, 0, 128);
		reconnectDelaySeconds = clamp(reconnectDelaySeconds, 3, 600);
		hudScale = Math.max(0.5, Math.min(2.0, hudScale));
		accent = Math.max(0, accent);

		if (rtpCommand == null) rtpCommand = d.rtpCommand;
		if (rtpMenuKeyword == null) rtpMenuKeyword = d.rtpMenuKeyword;
		if (sellCommand == null) sellCommand = d.sellCommand;
		if (sellConfirmKeyword == null) sellConfirmKeyword = d.sellConfirmKeyword;
		if (payTarget == null) payTarget = d.payTarget;
		if (balanceCommand == null) balanceCommand = d.balanceCommand;
		if (payCommand == null || payCommand.isBlank()) payCommand = d.payCommand;
		if (payConfirmKeyword == null) payConfirmKeyword = d.payConfirmKeyword;
		if (ignoredPlayers == null) ignoredPlayers = d.ignoredPlayers;
		if (raidFilter == null) raidFilter = d.raidFilter;
		if (keepItems == null) keepItems = d.keepItems;
		if (sellMode == null) sellMode = d.sellMode;
		if (lowHealthAction == null) lowHealthAction = d.lowHealthAction;
		if (playerNearbyAction == null) playerNearbyAction = d.playerNearbyAction;
		if (hudCorner == null) hudCorner = d.hudCorner;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
