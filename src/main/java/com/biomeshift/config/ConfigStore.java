package com.biomeshift.config;

import com.biomeshift.BiomeShift;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code config/biomeshift.json}, creating it from bundled defaults on first run. */
public final class ConfigStore {
	public static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("biomeshift.json");
	private static final String DEFAULTS_RESOURCE = "/biomeshift_defaults.json";
	private static final Pattern ENABLED_PATTERN = Pattern.compile("\"enabled\"\\s*:\\s*(?:true|false)");
	private static final Gson GSON = new GsonBuilder()
			.setLenient()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private ConfigStore() {
	}

	public static ConfigData loadOrCreate() {
		try {
			if (!Files.exists(PATH)) {
				ConfigData defaults = bundledDefaults();
				save(defaults);
				BiomeShift.LOG.info("created {}", PATH);
				return defaults;
			}
			String json = Files.readString(PATH, StandardCharsets.UTF_8);
			ConfigData data = GSON.fromJson(json, ConfigData.class);
			return data == null ? bundledDefaults() : data;
		} catch (IOException | RuntimeException e) {
			BiomeShift.LOG.error("could not read {}; using bundled defaults", PATH, e);
			return bundledDefaults();
		}
	}

	public static void save(ConfigData data) throws IOException {
		Files.createDirectories(PATH.getParent());
		Files.writeString(PATH, ConfigTemplate.render(data), StandardCharsets.UTF_8);
	}

	/**
	 * Flips only the {@code enabled} flag in place, so hand-edited comments and
	 * formatting survive; the rest of the file is never re-rendered. Falls back to
	 * creating the file from bundled defaults when it does not exist yet.
	 *
	 * @return false when the file has no {@code enabled} key to patch
	 */
	public static boolean saveEnabled(boolean enabled) throws IOException {
		if (!Files.exists(PATH)) {
			ConfigData data = bundledDefaults();
			data.enabled = enabled;
			save(data);
			return true;
		}
		String json = Files.readString(PATH, StandardCharsets.UTF_8);
		Matcher matcher = ENABLED_PATTERN.matcher(json);
		if (!matcher.find()) return false;
		Files.writeString(PATH, matcher.replaceFirst("\"enabled\": " + enabled), StandardCharsets.UTF_8);
		return true;
	}

	private static ConfigData bundledDefaults() {
		ConfigData data = new ConfigData();
		try (InputStream in = ConfigStore.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
			if (in == null) {
				BiomeShift.LOG.warn("bundled defaults {} missing", DEFAULTS_RESOURCE);
				return data;
			}
			ConfigData bundled = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ConfigData.class);
			if (bundled != null) {
				if (bundled.weights != null) data.weights = bundled.weights;
				if (bundled.dimensions != null) data.dimensions = bundled.dimensions;
			}
		} catch (IOException | RuntimeException e) {
			BiomeShift.LOG.warn("could not read bundled defaults", e);
		}
		return data;
	}

	/** Parses an identifier, logging and returning null when malformed. */
	public static Identifier parseId(String raw, String what) {
		try {
			return Identifier.parse(raw);
		} catch (RuntimeException e) {
			BiomeShift.LOG.warn("invalid {} id '{}': skipping", what, raw);
			return null;
		}
	}
}
