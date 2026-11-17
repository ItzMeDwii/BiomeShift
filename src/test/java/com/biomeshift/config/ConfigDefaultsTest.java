package com.biomeshift.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the shipped defaults and the commented template's key set. */
class ConfigDefaultsTest {
	@Test
	void codeDefaultsMatchDocumentedBehaviour() {
		ConfigData data = new ConfigData();
		assertTrue(data.enabled);
		assertEquals("threshold", data.mode);
		assertEquals("column", data.verticalScope);
		assertEquals(List.of("minecraft:overworld"), data.dimensions);
		assertEquals(256, data.wandMaxChunks);
	}

	@Test
	void templateMentionsEveryKey() {
		String text = ConfigTemplate.render(new ConfigData());
		for (String key : List.of("enabled", "mode", "threshold", "hysteresis", "scope",
				"verticalScope", "wandMaxChunks", "cooldownTicks", "maxSectionsPerTick",
				"rescanIntervalTicks", "rescanWindowTicks", "dimensions", "weights")) {
			assertTrue(text.contains("\"" + key + "\""), "template is missing " + key);
		}
		assertTrue(text.contains("// Dimensions where the mod runs"),
				"dimensions must stay documented");
	}
}
