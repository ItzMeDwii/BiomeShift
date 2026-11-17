package com.biomeshift.score;

/** Pure scoring rules; no Minecraft types, so it is unit-testable. */
public final class SectionScorer {
	private SectionScorer() {
	}

	public record Decision(int winner, float score, float currentScore) {
		public boolean changes() {
			return winner >= 0;
		}
	}

	/**
	 * @param scores       accumulated weight per biome index
	 * @param currentIndex index of the section's current biome, or -1 when unknown
	 * @param eligible     per biome index: the section carries the biome's trait roles
	 */
	public static Decision decide(float[] scores, int currentIndex, float threshold, float hysteresis,
			boolean[] eligible) {
		int current = currentIndex >= 0 && currentIndex < scores.length ? currentIndex : -1;
		float currentScore = current >= 0 ? scores[current] : 0f;

		int best = -1;
		float bestScore = 0f;
		for (int i = 0; i < scores.length; i++) {
			if (eligible != null && i < eligible.length && !eligible[i]) continue;
			if (scores[i] > bestScore) {
				bestScore = scores[i];
				best = i;
			}
		}

		if (best < 0 || bestScore < threshold) return new Decision(-1, bestScore, currentScore);
		if (best == current) return new Decision(-1, bestScore, currentScore);
		if (current >= 0 && bestScore <= currentScore + hysteresis) return new Decision(-1, bestScore, currentScore);
		return new Decision(best, bestScore, currentScore);
	}
}
