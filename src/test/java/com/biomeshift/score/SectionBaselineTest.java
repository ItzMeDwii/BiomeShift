package com.biomeshift.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SectionBaselineTest {
	@Test
	void deltaIsCurrentMinusBaseline() {
		float[] scores = {10f, 4f, 0f};
		float[] baseline = {4f, 1f, 0f};
		assertArrayEquals(new float[]{6f, 3f, 0f}, SectionBaseline.delta(scores, baseline));
	}

	@Test
	void breakingNaturalBlocksClampsToZero() {
		float[] scores = {4f, 2f};
		float[] baseline = {10f, 2f};
		assertArrayEquals(new float[]{0f, 0f}, SectionBaseline.delta(scores, baseline));
	}
}
