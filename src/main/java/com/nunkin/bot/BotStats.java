package com.nunkin.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BotStats {
	public static final class Totals {
		public long sandMined;
		public long sandSold;
		public double earned;
		public double paid;
		public long rtps;
		public long cycles;
		public long runtimeSeconds;
		public long chestsRaided;
		public long itemsLooted;

		void reset() {
			sandMined = sandSold = rtps = cycles = runtimeSeconds = chestsRaided = itemsLooted = 0;
			earned = paid = 0;
		}
	}

	public static final class CycleRecord {
		public int index;
		public long startedAt;
		public int durationSeconds;
		public int mined;
		public int sold;
		public double earned;
		public double paid;
		public int rtps;
	}

	private static final class Persisted {
		Totals lifetime = new Totals();
		List<CycleRecord> history = new ArrayList<>();
		double pendingPayout;
		long pendingSince;
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("nunkin");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nunkin-sandbot-stats.json");
	private static final int MAX_HISTORY = 60;

	public final Totals session = new Totals();
	public Totals lifetime = new Totals();
	public List<CycleRecord> history = new ArrayList<>();
	/** Money that should have been paid out but the server refused (recipient offline). */
	public double pendingPayout;
	public long pendingSince;
	private CycleRecord current;
	private long sessionStart = -1;

	public static BotStats load() {
		BotStats stats = new BotStats();
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				Persisted p = GSON.fromJson(reader, Persisted.class);
				if (p != null) {
					if (p.lifetime != null) stats.lifetime = p.lifetime;
					if (p.history != null) stats.history = p.history;
					stats.pendingPayout = p.pendingPayout;
					stats.pendingSince = p.pendingSince;
				}
			} catch (Exception e) {
				LOGGER.warn("Could not read {}", FILE, e);
			}
		}
		return stats;
	}

	public void save() {
		Persisted p = new Persisted();
		p.lifetime = lifetime;
		p.history = history;
		p.pendingPayout = pendingPayout;
		p.pendingSince = pendingSince;
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(p));
		} catch (IOException e) {
			LOGGER.warn("Could not save {}", FILE, e);
		}
	}

	public void startSession() {
		if (sessionStart < 0) {
			sessionStart = Util.getMillis();
		}
	}

	public void stopSession() {
		if (sessionStart >= 0) {
			long secs = (Util.getMillis() - sessionStart) / 1000;
			session.runtimeSeconds += secs;
			lifetime.runtimeSeconds += secs;
			sessionStart = -1;
		}
		endCycle();
		save();
	}

	public long sessionRuntimeSeconds() {
		long live = sessionStart >= 0 ? (Util.getMillis() - sessionStart) / 1000 : 0;
		return session.runtimeSeconds + live;
	}

	public long lifetimeRuntimeSeconds() {
		long live = sessionStart >= 0 ? (Util.getMillis() - sessionStart) / 1000 : 0;
		return lifetime.runtimeSeconds + live;
	}

	public void beginCycle(int index) {
		endCycle();
		current = new CycleRecord();
		current.index = index;
		current.startedAt = System.currentTimeMillis();
	}

	public void endCycle() {
		if (current == null) {
			return;
		}
		current.durationSeconds = (int) ((System.currentTimeMillis() - current.startedAt) / 1000);
		if (current.durationSeconds < 10 && current.mined == 0 && current.sold == 0 && current.paid == 0) {
			current = null;
			return;
		}
		history.add(current);
		while (history.size() > MAX_HISTORY) {
			history.remove(0);
		}
		session.cycles++;
		lifetime.cycles++;
		current = null;
	}

	public CycleRecord current() {
		return current;
	}

	public void addMined(int count) {
		session.sandMined += count;
		lifetime.sandMined += count;
		if (current != null) current.mined += count;
	}

	public void addSold(int count, double money) {
		session.sandSold += count;
		lifetime.sandSold += count;
		session.earned += money;
		lifetime.earned += money;
		if (current != null) {
			current.sold += count;
			current.earned += money;
		}
	}

	public void addPaid(double money) {
		session.paid += money;
		lifetime.paid += money;
		if (current != null) current.paid += money;
	}

	public void setPending(double amount) {
		if (amount > 0) {
			if (pendingPayout <= 0) {
				pendingSince = System.currentTimeMillis();
			}
			pendingPayout = amount;
		} else {
			pendingPayout = 0;
			pendingSince = 0;
		}
		save();
	}

	public void addRaid(int items) {
		session.chestsRaided++;
		lifetime.chestsRaided++;
		session.itemsLooted += items;
		lifetime.itemsLooted += items;
	}

	public void addRtp() {
		session.rtps++;
		lifetime.rtps++;
		if (current != null) current.rtps++;
	}

	public void resetSession() {
		session.reset();
		if (sessionStart >= 0) {
			sessionStart = Util.getMillis();
		}
	}

	public void resetLifetime() {
		lifetime.reset();
		history.clear();
		save();
	}
}
