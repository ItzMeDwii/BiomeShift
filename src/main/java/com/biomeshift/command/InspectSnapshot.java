package com.biomeshift.command;

import com.biomeshift.config.CompiledWeights;
import com.biomeshift.score.SectionBaseline;
import com.biomeshift.score.SectionScorer;
import com.biomeshift.score.TopScores;
import com.biomeshift.util.SectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** One reading of the section the player stands in. Shared by chat and the HUD. */
public record InspectSnapshot(
		int sectionX, int sectionY, int sectionZ, int chunkX, int chunkZ,
		String currentBiome, float currentScore,
		String topScores, int mappedBlocks, int nonAirBlocks,
		boolean changes, String winnerBiome, float winnerScore,
		float threshold, float hysteresis,
		String traitsBiome, int rolesCovered, int rolesRequired, String missingRoles, boolean eligible) {

	/** @return null when the player's chunk/section is not available. */
	public static InspectSnapshot of(ServerPlayer player, CompiledWeights weights) {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos pos = player.blockPosition();
		int sectionX = SectionPos.blockToSectionCoord(pos.getX());
		int sectionY = SectionPos.blockToSectionCoord(pos.getY());
		int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());

		LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
		if (chunk == null) return null;
		int sectionIndex = chunk.getSectionIndex(pos.getY());
		LevelChunkSection[] sections = chunk.getSections();
		if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) return null;
		LevelChunkSection section = sections[sectionIndex];

		Holder<Biome> current = section.getNoiseBiome(
				SectionPos.sectionRelative(pos.getX()) >> 2,
				SectionPos.sectionRelative(pos.getY()) >> 2,
				SectionPos.sectionRelative(pos.getZ()) >> 2);
		int currentIndex = weights.indexOf(current);

		String currentName = currentIndex >= 0 ? weights.nameOf(currentIndex) : null;
		if (currentName == null) {
			Identifier id = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(current.value());
			currentName = id != null ? id.toString() : "<unknown>";
		}

		CompiledWeights.ScanResult scan = weights.scan(section);
		float[] scores = effectiveScores(level, sectionX, sectionY, sectionZ, scan.scores());
		boolean overworld = level.dimension().equals(Level.OVERWORLD);
		boolean[] eligible = weights.eligible(scan.roles(), overworld);
		SectionScorer.Decision decision = weights.decide(scores, currentIndex, eligible);

		int shown = decision.changes() ? decision.winner() : TopScores.indices(scores, 1)[0];
		int requiredMask = shown >= 0 ? weights.requiredRoles(shown, overworld) : 0;
		int coveredMask = requiredMask & scan.roles();
		String traitsBiome = shown >= 0 && scores[shown] > 0 ? weights.nameOf(shown) : "";

		return new InspectSnapshot(
				sectionX, sectionY, sectionZ, chunk.getPos().x(), chunk.getPos().z(),
				currentName, currentIndex >= 0 ? scores[currentIndex] : 0f,
				topScores(weights, scores), scan.mappedBlocks(), scan.nonAirBlocks(),
				decision.changes(), decision.changes() ? weights.nameOf(decision.winner()) : "",
				decision.score(), weights.threshold(), weights.hysteresis(),
				traitsBiome, CompiledWeights.roleCount(coveredMask), CompiledWeights.roleCount(requiredMask),
				CompiledWeights.roleNames(requiredMask & ~scan.roles()),
				shown >= 0 && eligible[shown]);
	}

	/**
	 * Scores the mod acts on: current block scores minus the section's natural
	 * baseline. A section nobody has touched yet scores zero everywhere.
	 */
	private static float[] effectiveScores(ServerLevel level, int sectionX, int sectionY, int sectionZ,
			float[] scores) {
		SectionKey key = new SectionKey(level.dimension(), SectionPos.asLong(sectionX, sectionY, sectionZ));
		float[] baseline = SectionBaseline.get(key);
		return baseline == null ? new float[scores.length] : SectionBaseline.delta(scores, baseline);
	}

	private static String topScores(CompiledWeights weights, float[] scores) {
		int[] top = TopScores.indices(scores, 3);
		if (top[0] < 0) return "<no scored blocks>";
		StringBuilder builder = new StringBuilder();
		for (int index : top) {
			if (index < 0) break;
			if (builder.length() > 0) builder.append(", ");
			builder.append(weights.nameOf(index)).append(' ').append(format(scores[index]));
		}
		return builder.toString();
	}

	public static String format(float value) {
		return String.format("%.0f", value);
	}
}
