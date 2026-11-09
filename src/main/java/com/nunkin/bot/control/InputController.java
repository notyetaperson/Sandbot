package com.nunkin.bot.control;

/**
 * Movement keys the bot wants pressed. While active, {@code KeyboardInputMixin} replaces the player's
 * keyboard input with these values, so the bot works with any screen open and ignores stray key presses.
 */
public final class InputController {
	private static boolean active;
	public static boolean forward;
	public static boolean back;
	public static boolean left;
	public static boolean right;
	public static boolean jump;
	public static boolean sneak;
	public static boolean sprint;

	private InputController() {
	}

	public static boolean isActive() {
		return active;
	}

	public static void setActive(boolean value) {
		active = value;
		if (!value) {
			clearKeys();
		}
	}

	public static void clearKeys() {
		forward = back = left = right = jump = sneak = sprint = false;
	}
}
