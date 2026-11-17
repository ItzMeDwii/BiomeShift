package com.biomeshift.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionScorerTest {
	private static final float THRESHOLD = 10f;
	private static final float HYSTERESIS = 4f;

	@Test
	void belowThresholdNeverChanges() {
		float[] scores = {9f, 9.5f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, 0, THRESHOLD, HYSTERESIS, null);
		assertFalse(decision.changes());
	}

	@Test
	void unknownCurrentBiomeAcceptsAnyWinnerAboveThreshold() {
		float[] scores = {0f, 12f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, -1, THRESHOLD, HYSTERESIS, null);
		assertTrue(decision.changes());
		assertEquals(1, decision.winner());
	}

	@Test
	void hysteresisBlocksMarginalWins() {
		// challenger 12 vs current 9: 12 - 9 = 3 < 4 hysteresis, so the section stays.
		float[] scores = {9f, 12f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, 0, THRESHOLD, HYSTERESIS, null);
		assertFalse(decision.changes());
	}

	@Test
	void clearWinOvercomesHysteresis() {
		float[] scores = {9f, 14f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, 0, THRESHOLD, HYSTERESIS, null);
		assertTrue(decision.changes());
		assertEquals(1, decision.winner());
	}

	@Test
	void currentBiomeWinningByItselfIsNotAChange() {
		float[] scores = {20f, 3f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, 0, THRESHOLD, HYSTERESIS, null);
		assertFalse(decision.changes());
	}

	@Test
	void emptyScoresNeverChange() {
		SectionScorer.Decision decision = SectionScorer.decide(new float[0], 0, THRESHOLD, HYSTERESIS, null);
		assertFalse(decision.changes());
	}

	@Test
	void ineligibleBestIsSkipped() {
		float[] scores = {20f, 14f};
		boolean[] eligible = {false, true};
		SectionScorer.Decision decision = SectionScorer.decide(scores, -1, THRESHOLD, HYSTERESIS, eligible);
		assertEquals(1, decision.winner());
		assertEquals(14f, decision.score());
	}

	@Test
	void noEligibleBiomeNeverChanges() {
		float[] scores = {20f, 14f};
		boolean[] eligible = {false, false};
		SectionScorer.Decision decision = SectionScorer.decide(scores, -1, THRESHOLD, HYSTERESIS, eligible);
		assertFalse(decision.changes());
	}

	@Test
	void tieKeepsFirstHighestBiomeAndRespectsThreshold() {
		float[] scores = {11f, 11f};
		SectionScorer.Decision decision = SectionScorer.decide(scores, -1, THRESHOLD, HYSTERESIS, null);
		assertEquals(0, decision.winner());
		assertEquals(11f, decision.score());
	}
}
