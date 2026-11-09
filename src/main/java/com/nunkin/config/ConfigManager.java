package com.nunkin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("nunkin");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nunkin-sandbot.json");

	private static BotConfig config = new BotConfig();
	private static boolean dirty;

	private ConfigManager() {
	}

	public static BotConfig get() {
		return config;
	}

	public static void load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE)) {
				BotConfig loaded = GSON.fromJson(reader, BotConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				LOGGER.warn("Could not read {}, using defaults", FILE, e);
			}
		}
		config.sanitize();
		save();
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void saveIfDirty() {
		if (dirty) {
			save();
		}
	}

	public static void save() {
		dirty = false;
		config.sanitize();
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(config));
		} catch (IOException e) {
			LOGGER.warn("Could not save {}", FILE, e);
		}
	}

	public static void resetToDefaults() {
		config = new BotConfig();
		save();
	}
}
