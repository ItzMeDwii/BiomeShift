package com.biomeshift.score;

/** Picks the highest-scoring biome indices without relying on a comparator. */
public final class TopScores {
	private TopScores() {
	}

	/** @return up to {@code n} distinct indices, best first, -1 padding; zero scores are ignored. */
	public static int[] indices(float[] scores, int n) {
		int[] top = new int[n];
		java.util.Arrays.fill(top, -1);
		for (int i = 0; i < scores.length; i++) {
			float score = scores[i];
			if (score <= 0) continue;
			int at = -1;
			for (int k = 0; k < n; k++) {
				if (top[k] < 0 || score > scores[top[k]]) {
					at = k;
					break;
				}
			}
			if (at < 0) continue;
			for (int k = n - 1; k > at; k--) top[k] = top[k - 1];
			top[at] = i;
		}
		return top;
	}
}
