package com.nunkin.bot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BotLog {
	public enum Level {
		DEBUG("DBG", 0xFF6B7280),
		INFO("INF", 0xFF9CA3AF),
		SUCCESS("OK", 0xFF34D399),
		WARN("WRN", 0xFFFBBF24),
		ERROR("ERR", 0xFFF87171),
		SERVER("SRV", 0xFF60A5FA);

		public final String tag;
		public final int color;

		Level(String tag, int color) {
			this.tag = tag;
			this.color = color;
		}
	}

	public record Entry(long id, String clock, Level level, String message) {
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("nunkin");
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final int MAX_ENTRIES = 600;
	private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
	private static long counter;

	private BotLog() {
	}

	public static synchronized void add(Level level, String message) {
		ENTRIES.addLast(new Entry(++counter, LocalTime.now().format(CLOCK), level, message));
		while (ENTRIES.size() > MAX_ENTRIES) {
			ENTRIES.removeFirst();
		}
		switch (level) {
			case WARN -> LOGGER.warn("[SandBot] {}", message);
			case ERROR -> LOGGER.error("[SandBot] {}", message);
			default -> LOGGER.info("[SandBot] {}", message);
		}
	}

	public static void debug(String message) {
		add(Level.DEBUG, message);
	}

	public static void info(String message) {
		add(Level.INFO, message);
	}

	public static void success(String message) {
		add(Level.SUCCESS, message);
	}

	public static void warn(String message) {
		add(Level.WARN, message);
	}

	public static void error(String message) {
		add(Level.ERROR, message);
	}

	public static void server(String message) {
		add(Level.SERVER, message);
	}

	public static synchronized List<Entry> snapshot() {
		return new ArrayList<>(ENTRIES);
	}

	public static synchronized long lastId() {
		return counter;
	}

	public static synchronized void clear() {
		ENTRIES.clear();
	}

	public static synchronized String export() {
		StringBuilder sb = new StringBuilder();
		for (Entry e : ENTRIES) {
			sb.append(e.clock()).append(" [").append(e.level().tag).append("] ").append(e.message()).append('\n');
		}
		return sb.toString();
	}
}
