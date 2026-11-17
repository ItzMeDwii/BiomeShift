package com.biomeshift.util;

import net.minecraft.core.BlockPos;

/** Inclusive chunk rectangle between two block positions (wand selections). */
public record ChunkArea(int minX, int minZ, int maxX, int maxZ) {
	public static ChunkArea between(BlockPos first, BlockPos second) {
		return new ChunkArea(
				Math.min(first.getX(), second.getX()) >> 4,
				Math.min(first.getZ(), second.getZ()) >> 4,
				Math.max(first.getX(), second.getX()) >> 4,
				Math.max(first.getZ(), second.getZ()) >> 4);
	}

	public long chunks() {
		return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
	}

	public boolean exceeds(int limit) {
		return chunks() > limit;
	}
}
