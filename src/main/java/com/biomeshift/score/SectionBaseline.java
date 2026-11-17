package com.biomeshift.score;

import com.biomeshift.util.SectionKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-section natural-terrain baseline. The first time a player touches a section
 * its block scores are recorded here (minus the blocks that player just placed);
 * afterwards only the difference against that baseline counts, so untouched
 * terrain can never win a section on its own.
 */
public final class SectionBaseline {
	private static final int MAX_ENTRIES = 8192;
	private static final Map<SectionKey, float[]> BASELINES = new HashMap<>();

	private SectionBaseline() {
	}

	/** @return the stored baseline, or null when the section has never been touched. */
	public static float[] get(SectionKey key) {
		return BASELINES.get(key);
	}

	public static void put(SectionKey key, float[] baseline) {
		if (BASELINES.size() >= MAX_ENTRIES) BASELINES.clear();
		BASELINES.put(key, baseline);
	}

	/** Clamped current-minus-baseline, the only scores the mod acts on. */
	public static float[] delta(float[] scores, float[] baseline) {
		float[] delta = new float[scores.length];
		for (int i = 0; i < scores.length; i++) {
			delta[i] = Math.max(0f, scores[i] - baseline[i]);
		}
		return delta;
	}

	public static void clear() {
		BASELINES.clear();
	}
}
