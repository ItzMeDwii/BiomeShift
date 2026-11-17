package com.biomeshift.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTemplateTest {
	private static final Gson GSON = new GsonBuilder().setLenient().create();

	@Test
	void renderedConfigParsesBackWithComments() {
		ConfigData data = new ConfigData();
		data.threshold = 15;
		data.verticalScope = "above";
		data.dimensions = List.of("minecraft:overworld", "minecraft:the_nether");
		data.weights = Map.of("minecraft:plains", Map.of("minecraft:short_grass", 6.0));

		String text = ConfigTemplate.render(data);
		assertTrue(text.contains("// Master switch"), "comments must be written");
		assertTrue(text.contains("verticalScope"), "new keys must appear in the template");

		ConfigData parsed = GSON.fromJson(text, ConfigData.class);
		assertTrue(parsed.enabled);
		assertEquals(15, parsed.threshold);
		assertEquals("above", parsed.verticalScope);
		assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), parsed.dimensions);
		assertEquals(6.0, parsed.weights.get("minecraft:plains").get("minecraft:short_grass"));
	}
}
