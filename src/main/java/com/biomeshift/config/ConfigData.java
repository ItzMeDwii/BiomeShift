package com.biomeshift.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk shape of {@code config/biomeshift.json}. Field initialisers are the
 * defaults used when the file is created; Gson overwrites only present keys.
 */
public final class ConfigData {
	public boolean enabled = true;
	/** {@code threshold} or {@code instant}. */
	public String mode = "threshold";
	/** Minimum section score a biome needs before it may win. */
	public int threshold = 12;
	/** Extra score the current biome holds, to stop flip-flopping. */
	public int hysteresis = 4;
	/** Only {@code section} is accepted; anything else is rejected at load. */
	public String scope = "section";
	/**
	 * How far a flip reaches vertically: {@code column} (whole chunk column, default),
	 * {@code above} (the flipped section and everything above it) or {@code section}.
	 */
	public String verticalScope = "column";
	/** Upper bound on chunks a wand selection may sync in one click. */
	public int wandMaxChunks = 256;
	/** Minimum ticks between two biome resends of the same chunk. */
	public int cooldownTicks = 40;
	/** Sections scanned per server tick. */
	public int maxSectionsPerTick = 8;
	/** How often a warm section (sapling planted, tree not grown yet) is checked. */
	public int rescanIntervalTicks = 400;
	/** How long a planted sapling keeps its section warm, in ticks (72000 = 1 hour). */
	public int rescanWindowTicks = 72000;
	/** Dimensions where the mod runs; overworld only unless changed. */
	public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
	/** biome id -> (block id -> weight). Block ids may also be {@code #tag}. */
	public Map<String, Map<String, Double>> weights = new LinkedHashMap<>();
}
