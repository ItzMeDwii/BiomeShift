package com.biomeshift.trigger;

import com.biomeshift.BiomeShift;
import com.biomeshift.config.CompiledWeights;
import com.biomeshift.rewrite.BiomeRewriter;
import com.biomeshift.score.SectionBaseline;
import com.biomeshift.score.SectionScorer;
import com.biomeshift.util.SectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dirty-section queue. Placements only mark a section; scanning, rewriting and
 * client resends happen on the server tick under a per-tick budget.
 *
 * <p>Scores are always deltas against the section's natural baseline (see
 * {@link SectionBaseline}): untouched terrain contributes nothing, so a section
 * only changes biome when players actually build.
 */
public final class PendingSections {
	private record Dirty(SectionKey key, List<BlockPos> positions) {
	}

	private record ChunkKey(ResourceKey<Level> dimension, long chunk) {
	}

	private record Resend(SectionKey key, LevelChunk chunk) {
	}

	/** A section holding a player-placed growth marker; polled cheaply until growth is seen. */
	private record Warm(SectionKey key, BlockPos pos, Block block, long expiresAt, long nextCheck) {
	}

	private static final Map<SectionKey, Dirty> DIRTY = new HashMap<>();
	private static final Map<SectionKey, Warm> WARM = new HashMap<>();
	private static final Deque<Resend> RESEND_QUEUE = new ArrayDeque<>();
	private static final Map<ChunkKey, Long> LAST_RESEND = new HashMap<>();
	/** Hard cap so a huge tree farm cannot grow the polling set without bound. */
	private static final int MAX_WARM = 256;
	private static long tick;

	private PendingSections() {
	}

	public static void mark(ResourceKey<Level> dimension, long section, BlockPos pos) {
		SectionKey key = new SectionKey(dimension, section);
		Dirty dirty = DIRTY.computeIfAbsent(key, k -> new Dirty(k, new ArrayList<>()));
		BlockPos immutable = pos.immutable();
		if (!dirty.positions().contains(immutable)) dirty.positions().add(immutable);
	}

	/**
	 * Keeps a section under cheap observation after a player plants a growth marker
	 * (sapling/fungus). Each poll reads one block; a full scan only happens once the
	 * marker is gone, i.e. when something actually grew or was broken.
	 */
	public static void markWarm(ResourceKey<Level> dimension, long section, BlockPos pos, Block block,
			int windowTicks, int intervalTicks) {
		if (windowTicks <= 0) return;
		SectionKey key = new SectionKey(dimension, section);
		if (WARM.size() >= MAX_WARM && !WARM.containsKey(key)) {
			evictSoonest();
		}
		long now = tick;
		WARM.put(key, new Warm(key, pos.immutable(), block, now + windowTicks, now + intervalTicks));
	}

	private static void evictSoonest() {
		SectionKey soonest = null;
		long soonestAt = Long.MAX_VALUE;
		for (Warm warm : WARM.values()) {
			if (warm.expiresAt() < soonestAt) {
				soonestAt = warm.expiresAt();
				soonest = warm.key();
			}
		}
		if (soonest != null) WARM.remove(soonest);
	}

	public static void clear() {
		DIRTY.clear();
		WARM.clear();
		RESEND_QUEUE.clear();
		LAST_RESEND.clear();
		SectionBaseline.clear();
		tick = 0;
	}

	public static void tick(MinecraftServer server) {
		tick++;
		CompiledWeights weights = BiomeShift.weights();
		if (weights == null) return;

		if (tick % 600 == 0 && !LAST_RESEND.isEmpty()) {
			long cutoff = tick - Math.max(600L, weights.cooldownTicks() * 10L);
			LAST_RESEND.entrySet().removeIf(entry -> entry.getValue() < cutoff);
		}

		if (!WARM.isEmpty()) pollWarm(server, weights);

		if (!DIRTY.isEmpty() && weights.enabled()) {
			int budget = weights.maxSectionsPerTick();
			Iterator<Dirty> it = DIRTY.values().iterator();
			List<Dirty> deferred = new ArrayList<>();
			while (it.hasNext() && budget > 0) {
				Dirty dirty = it.next();
				it.remove();
				if (!process(server, weights, dirty)) {
					deferred.add(dirty);
				}
				budget--;
			}
			for (Dirty d : deferred) DIRTY.putIfAbsent(d.key(), d);
		}

		flushResends(server, weights);
	}

	/**
	 * Cheap poll: one block read per warm section per interval. A full scan is only
	 * queued once the marker block is gone (tree grew or sapling was broken).
	 */
	private static void pollWarm(MinecraftServer server, CompiledWeights weights) {
		int interval = weights.rescanIntervalTicks();
		Iterator<Warm> it = WARM.values().iterator();
		while (it.hasNext()) {
			Warm warm = it.next();
			if (tick < warm.nextCheck()) continue;
			if (tick > warm.expiresAt()) {
				it.remove();
				continue;
			}

			ServerLevel level = server.getLevel(warm.key().dimension());
			if (level == null) {
				it.remove();
				continue;
			}
			LevelChunk chunk = level.getChunkSource().getChunkNow(
					SectionPos.x(warm.key().section()), SectionPos.z(warm.key().section()));
			if (chunk == null) {
				// Chunk unloaded: do not force-load, just look again later.
				WARM.put(warm.key(), new Warm(warm.key(), warm.pos(), warm.block(), warm.expiresAt(), tick + interval));
				continue;
			}
			if (level.getBlockState(warm.pos()).getBlock() == warm.block()) {
				WARM.put(warm.key(), new Warm(warm.key(), warm.pos(), warm.block(), warm.expiresAt(), tick + interval));
				continue;
			}
			mark(warm.key().dimension(), warm.key().section(), warm.pos());
			it.remove();
		}
	}

	/** @return false when the section could not be handled yet and should stay dirty. */
	private static boolean process(MinecraftServer server, CompiledWeights weights, Dirty dirty) {
		ServerLevel level = server.getLevel(dirty.key().dimension());
		if (level == null || !weights.allows(level.dimension())) return true;

		int sectionX = SectionPos.x(dirty.key().section());
		int sectionY = SectionPos.y(dirty.key().section());
		int sectionZ = SectionPos.z(dirty.key().section());

		LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
		if (chunk == null) return true; // never force-load

		int sectionIndex = chunk.getSectionIndex(sectionY << 4);
		LevelChunkSection[] sections = chunk.getSections();
		if (sectionIndex < 0 || sectionIndex >= sections.length) return true;
		LevelChunkSection section = sections[sectionIndex];
		if (section == null) return true;

		BlockPos sample = dirty.positions().get(dirty.positions().size() - 1);
		int localX = SectionPos.sectionRelative(sample.getX());
		int localY = SectionPos.sectionRelative(sample.getY());
		int localZ = SectionPos.sectionRelative(sample.getZ());
		Holder<Biome> currentHolder = section.getNoiseBiome(localX >> 2, localY >> 2, localZ >> 2);
		int currentIndex = weights.indexOf(currentHolder);

		int winnerIndex;
		if (weights.instant()) {
			BlockState placed = level.getBlockState(sample);
			winnerIndex = instantWinner(weights, placed);
			if (winnerIndex == currentIndex) winnerIndex = -1;
		} else {
			CompiledWeights.ScanResult scanned = weights.scan(section);
			float[] baseline = baselineFor(weights, level, dirty, scanned.scores());
			float[] delta = SectionBaseline.delta(scanned.scores(), baseline);
			boolean[] eligible = weights.eligible(scanned.roles(), level.dimension().equals(Level.OVERWORLD));
			SectionScorer.Decision decision = weights.decide(delta, currentIndex, eligible);
			winnerIndex = decision.changes() ? decision.winner() : -1;
			if (winnerIndex >= 0) {
				BiomeShift.LOG.debug("[{}] section {} winner {} score {} current {}",
						BiomeShift.MOD_ID, dirty.key().section(), decision.winner(), decision.score(), decision.currentScore());
			}
		}

		if (winnerIndex < 0) return true;

		Holder<Biome> target = weights.biome(winnerIndex);
		int top = sections.length - 1;
		int from;
		int to;
		switch (weights.verticalScope()) {
			case "section" -> {
				from = sectionIndex;
				to = sectionIndex;
			}
			case "above" -> {
				from = sectionIndex;
				to = top;
			}
			default -> {
				from = 0;
				to = top;
			}
		}
		if (!BiomeRewriter.applyRange(chunk, from, to, target)) return true;

		ChunkKey chunkKey = new ChunkKey(dirty.key().dimension(), chunk.getPos().pack());
		Long last = LAST_RESEND.get(chunkKey);
		if (last != null && tick - last < weights.cooldownTicks()) {
			RESEND_QUEUE.add(new Resend(dirty.key(), chunk));
		} else {
			LAST_RESEND.put(chunkKey, tick);
			BiomeRewriter.resend(level, chunk);
		}
		return true;
	}

	/**
	 * Baseline for a section: its block scores with the placements that triggered this
	 * first scan removed, so those blocks count as player contribution and everything
	 * that was already there counts as nothing.
	 */
	private static float[] baselineFor(CompiledWeights weights, ServerLevel level, Dirty dirty,
			float[] scores) {
		float[] baseline = SectionBaseline.get(dirty.key());
		if (baseline != null) return baseline;

		baseline = scores.clone();
		for (BlockPos pos : dirty.positions()) {
			CompiledWeights.WeightSet set = weights.weightsOf(level.getBlockState(pos).getBlock());
			if (set == null) continue;
			for (int i = 0; i < set.biomes().length; i++) {
				baseline[set.biomes()[i]] -= set.weights()[i];
			}
		}
		SectionBaseline.put(dirty.key(), baseline);
		return baseline;
	}

	private static int instantWinner(CompiledWeights weights, BlockState placed) {
		CompiledWeights.WeightSet set = weights.weightsOf(placed.getBlock());
		if (set == null || set.biomes().length == 0) return -1;
		int best = -1;
		float bestWeight = 0;
		for (int i = 0; i < set.biomes().length; i++) {
			if (set.weights()[i] > bestWeight) {
				bestWeight = set.weights()[i];
				best = set.biomes()[i];
			}
		}
		return best;
	}

	private static void flushResends(MinecraftServer server, CompiledWeights weights) {
		if (RESEND_QUEUE.isEmpty()) return;
		Set<ChunkKey> seen = new HashSet<>();
		int budget = weights.maxSectionsPerTick();
		int attempts = RESEND_QUEUE.size();
		for (int i = 0; i < attempts && budget > 0; i++) {
			Resend resend = RESEND_QUEUE.poll();
			if (resend == null) break;
			ChunkKey chunkKey = new ChunkKey(resend.key().dimension(), resend.chunk().getPos().pack());
			if (!seen.add(chunkKey)) continue;
			ServerLevel level = server.getLevel(resend.key().dimension());
			if (level == null) continue;
			Long last = LAST_RESEND.get(chunkKey);
			if (last != null && tick - last < weights.cooldownTicks()) {
				RESEND_QUEUE.add(resend);
				continue;
			}
			LAST_RESEND.put(chunkKey, tick);
			BiomeRewriter.resend(level, resend.chunk());
			budget--;
		}
	}
}
