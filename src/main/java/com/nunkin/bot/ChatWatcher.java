package com.nunkin.bot;

import com.nunkin.util.TextUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Keeps a short history of incoming chat so tasks can look for server replies after sending a command. */
public final class ChatWatcher {
	/**
	 * @param commandError the server rejected a command (unknown command, bad arguments, no permission)
	 */
	public record Line(long id, long time, String raw, String norm, boolean overlay, boolean fromPlayer, boolean commandError) {
	}

	private static final int MAX_LINES = 250;
	private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
	private static long nextId = 1;
	private static boolean ignoringOwnMessages;

	private ChatWatcher() {
	}

	public static void onGameMessage(Component message, boolean overlay) {
		add(message, overlay, false);
	}

	public static void onPlayerChat(Component message) {
		add(message, false, true);
	}

	/** Runs {@code action} without recording the chat lines it produces (used for the bot's own messages). */
	public static void withoutCapture(Runnable action) {
		ignoringOwnMessages = true;
		try {
			action.run();
		} finally {
			ignoringOwnMessages = false;
		}
	}

	private static synchronized void add(Component message, boolean overlay, boolean fromPlayer) {
		if (ignoringOwnMessages) {
			return;
		}
		String raw = message.getString();
		if (raw.isBlank()) {
			return;
		}
		String norm = TextUtil.normalize(raw);
		if (norm.contains("<--[here]")) {
			// Vanilla echoes the rejected command text after a syntax error; never parse it as a reply.
			return;
		}
		boolean commandError = !fromPlayer && TextUtil.containsAny(norm, "unknown or incomplete command", "unknown command",
			"incorrect argument for command", "you do not have permission", "you don't have permission", "no permission");
		Line line = new Line(nextId++, Util.getMillis(), raw, norm, overlay, fromPlayer, commandError);
		LINES.addLast(line);
		while (LINES.size() > MAX_LINES) {
			LINES.removeFirst();
		}
		SandBot.get().onChatLine(line);
	}

	/** Returns an id; every line received afterwards has an id greater or equal to it. */
	public static synchronized long mark() {
		return nextId;
	}

	public static synchronized List<Line> since(long mark) {
		List<Line> out = new ArrayList<>();
		for (Line line : LINES) {
			if (line.id() >= mark) {
				out.add(line);
			}
		}
		return out;
	}

	public static synchronized Line findSince(long mark, Predicate<Line> predicate) {
		for (Line line : LINES) {
			if (line.id() >= mark && predicate.test(line)) {
				return line;
			}
		}
		return null;
	}

	public static Line commandErrorSince(long mark) {
		return findSince(mark, Line::commandError);
	}
}
