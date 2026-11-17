package com.biomeshift.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopScoresTest {
	@Test
	void picksTopThreeInOrder() {
		float[] scores = {5f, 9f, 1f, 7f, 0f, 3f};
		int[] top = TopScores.indices(scores, 3);
		assertEquals(1, top[0]);
		assertEquals(3, top[1]);
		assertEquals(0, top[2]);
	}

	@Test
	void padsWhenFewerScores() {
		float[] scores = {0f, 4f, 0f};
		int[] top = TopScores.indices(scores, 3);
		assertEquals(1, top[0]);
		assertEquals(-1, top[1]);
		assertEquals(-1, top[2]);
	}

	@Test
	void ignoresZeroAndNegative() {
		float[] scores = {0f, -2f};
		int[] top = TopScores.indices(scores, 3);
		assertEquals(-1, top[0]);
	}
}
