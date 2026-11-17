package com.biomeshift.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalScopeTest {
	@Test
	void acceptsDocumentedValues() {
		assertEquals("column", CompiledWeights.normalizeVerticalScope("column"));
		assertEquals("above", CompiledWeights.normalizeVerticalScope("ABOVE"));
		assertEquals("section", CompiledWeights.normalizeVerticalScope("section"));
	}

	@Test
	void nullMeansColumn() {
		assertEquals("column", CompiledWeights.normalizeVerticalScope(null));
	}

	@Test
	void rejectsUnknownValue() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> CompiledWeights.normalizeVerticalScope("chunk"));
		assertTrue(error.getMessage().contains("verticalScope"));
	}
}
