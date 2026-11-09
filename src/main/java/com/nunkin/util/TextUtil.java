package com.nunkin.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtil {
	private static final Pattern FORMATTING = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern MONEY_DOLLAR = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?(?![a-z])");
	private static final Pattern MONEY_PLAIN = Pattern.compile("(?<![a-z0-9.,])([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?(?![a-z0-9])");
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])");
	private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");

	// Small-caps glyphs that many servers use for "fancy" text, mapped back to ASCII.
	private static final String SMALL_CAPS = "\u1d00\u0299\u1d04\u1d05\u1d07\ua730\u0262\u029c\u026a\u1d0a\u1d0b\u029f\u1d0d\u0274\u1d0f\u1d18\u01eb\u0280\ua731\u1d1b\u1d1c\u1d20\u1d21x\u028f\u1d22";

	public record Money(double value, double unit) {
	}

	private TextUtil() {
	}

	public static String normalize(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		String stripped = FORMATTING.matcher(text).replaceAll("");
		StringBuilder sb = new StringBuilder(stripped.length());
		for (int i = 0; i < stripped.length(); i++) {
			char c = stripped.charAt(i);
			int idx = SMALL_CAPS.indexOf(c);
			sb.append(idx >= 0 ? (char) ('a' + idx) : c);
		}
		return WHITESPACE.matcher(sb.toString().toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	public static Money findMoney(String normalized, boolean allowPlainNumbers) {
		Matcher m = MONEY_DOLLAR.matcher(normalized);
		if (m.find()) {
			return toMoney(m.group(1), m.group(2));
		}
		if (allowPlainNumbers) {
			m = MONEY_PLAIN.matcher(normalized);
			if (m.find()) {
				return toMoney(m.group(1), m.group(2));
			}
		}
		return null;
	}

	public static Money parseMoneyInput(String input) {
		if (input == null) {
			return null;
		}
		return findMoney(normalize(input), true);
	}

	private static Money toMoney(String number, String suffix) {
		String clean = number.replace(",", "");
		double value;
		try {
			value = Double.parseDouble(clean);
		} catch (NumberFormatException e) {
			return null;
		}
		int dot = clean.indexOf('.');
		int decimals = dot >= 0 ? clean.length() - dot - 1 : 0;
		double mult = suffixMultiplier(suffix);
		return new Money(value * mult, Math.pow(10, -decimals) * mult);
	}

	private static double suffixMultiplier(String suffix) {
		if (suffix == null || suffix.isEmpty()) {
			return 1;
		}
		return switch (suffix.charAt(0)) {
			case 'k' -> 1_000d;
			case 'm' -> 1_000_000d;
			case 'b' -> 1_000_000_000d;
			case 't' -> 1_000_000_000_000d;
			default -> 1;
		};
	}

	/** Parses things like "1m 30s", "45 seconds", "00:45". Returns -1 when no duration is present. */
	public static int parseDurationSeconds(String normalized) {
		int total = 0;
		boolean any = false;
		Matcher m = DURATION_PART.matcher(normalized);
		while (m.find()) {
			int n = Integer.parseInt(m.group(1));
			String unit = m.group(2);
			if (unit.startsWith("h")) {
				total += n * 3600;
			} else if (unit.startsWith("m")) {
				total += n * 60;
			} else {
				total += n;
			}
			any = true;
		}
		if (any) {
			return total;
		}
		m = CLOCK.matcher(normalized);
		if (m.find()) {
			return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
		}
		return -1;
	}

	public static String money(double value) {
		double abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1_000_000_000_000d) return sign + "$" + trim(abs / 1_000_000_000_000d) + "T";
		if (abs >= 1_000_000_000d) return sign + "$" + trim(abs / 1_000_000_000d) + "B";
		if (abs >= 1_000_000d) return sign + "$" + trim(abs / 1_000_000d) + "M";
		if (abs >= 10_000d) return sign + "$" + trim(abs / 1_000d) + "K";
		return sign + "$" + number(Math.round(abs));
	}

	private static String trim(double v) {
		String s = String.format(Locale.ROOT, v >= 100 ? "%.0f" : v >= 10 ? "%.1f" : "%.2f", v);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}

	public static String number(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	/** Whole-number amount suitable for a /pay command (always rounded down). */
	public static String plainAmount(double value) {
		return new BigDecimal(value).setScale(0, RoundingMode.FLOOR).toPlainString();
	}

	public static String clock(long seconds) {
		seconds = Math.max(0, seconds);
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	public static String durationWords(long seconds) {
		seconds = Math.max(0, seconds);
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (h > 0) return String.format(Locale.ROOT, "%dh %02dm", h, m);
		if (m > 0) return String.format(Locale.ROOT, "%dm %02ds", m, s);
		return s + "s";
	}

	public static String stripSlash(String command) {
		String c = command == null ? "" : command.trim();
		while (c.startsWith("/")) {
			c = c.substring(1);
		}
		return c;
	}

	public static boolean containsAny(String haystack, String... needles) {
		for (String needle : needles) {
			if (haystack.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
