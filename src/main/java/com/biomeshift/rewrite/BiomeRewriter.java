package com.biomeshift.rewrite;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Writes biome cells for a section range and persists the chunk. */
public final class BiomeRewriter {
	private BiomeRewriter() {
	}

	/** Writes every section in {@code [fromIndex, toIndex]} and persists the chunk once. */
	public static boolean applyRange(LevelChunk chunk, int fromIndex, int toIndex, Holder<Biome> target) {
		LevelChunkSection[] sections = chunk.getSections();
		int low = Math.max(0, Math.min(fromIndex, toIndex));
		int high = Math.min(sections.length - 1, Math.max(fromIndex, toIndex));
		int quartMinX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
		int quartMinZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
		BiomeResolver resolver = (quartX, quartY, quartZ) -> target;

		boolean written = false;
		for (int index = low; index <= high; index++) {
			LevelChunkSection section = sections[index];
			if (section == null) continue;
			int quartMinY = QuartPos.fromBlock(chunk.getSectionYFromSectionIndex(index) << 4);
			// fillBiomesFromNoise walks all 64 cells of the section at 4x4x4 granularity.
			section.fillBiomesFromNoise(resolver, quartMinX, quartMinY, quartMinZ);
			written = true;
		}
		if (written) chunk.markUnsaved();
		return written;
	}

	/** One resend packet batch for many chunks (wand selections). */
	public static void resendAll(ServerLevel level, List<LevelChunk> chunks) {
		if (chunks.isEmpty()) return;
		List<ChunkAccess> payload = new ArrayList<>(chunks.size());
		payload.addAll(chunks);
		level.getChunkSource().chunkMap.resendBiomesForChunks(payload);
	}

	/** Tells every tracking client the chunk's biome data changed, so colours refresh. */
	public static void resend(ServerLevel level, LevelChunk chunk) {
		level.getChunkSource().chunkMap.resendBiomesForChunks(java.util.List.of(chunk));
	}
}
