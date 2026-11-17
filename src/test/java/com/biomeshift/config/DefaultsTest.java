package com.biomeshift.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the shipped defaults against typos that would silently disable a biome. */
class DefaultsTest {
	private static final Pattern ID = Pattern.compile("^minecraft:[a-z0-9_]+$");

	@Test
	void bundledDefaultsParseAndAreWellFormed() {
		InputStream in = DefaultsTest.class.getResourceAsStream("/biomeshift_defaults.json");
		assertNotNull(in, "biomeshift_defaults.json missing from the jar");
		ConfigData data;
		try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			data = new Gson().fromJson(reader, ConfigData.class);
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		assertNotNull(data);
		assertNotNull(data.weights);
		assertTrue(data.weights.size() >= 60, "expected the full vanilla biome set, got " + data.weights.size());

		for (Map.Entry<String, Map<String, Double>> biome : data.weights.entrySet()) {
			assertTrue(ID.matcher(biome.getKey()).matches(), "bad biome id: " + biome.getKey());
			assertFalse(biome.getValue().isEmpty(), "biome has no blocks: " + biome.getKey());
			for (Map.Entry<String, Double> block : biome.getValue().entrySet()) {
				assertTrue(ID.matcher(block.getKey()).matches(), "bad block id: " + block.getKey());
				assertTrue(block.getValue() > 0, "non-positive weight for " + block.getKey());
			}
		}
	}

	@Test
	void defaultsDoNotUseTagsOrWildcards() {
		InputStream in = DefaultsTest.class.getResourceAsStream("/biomeshift_defaults.json");
		assertNotNull(in);
		ConfigData data;
		try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			data = new Gson().fromJson(reader, ConfigData.class);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		for (Map<String, Double> blocks : data.weights.values()) {
			for (String id : blocks.keySet()) {
				assertFalse(id.startsWith("#") || id.equals("*"), "unsupported selector in defaults: " + id);
			}
		}
	}
}
