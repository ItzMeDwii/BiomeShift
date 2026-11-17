package com.biomeshift.util;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAreaTest {
	@Test
	void singleChunkSelection() {
		ChunkArea area = ChunkArea.between(new BlockPos(5, 64, 5), new BlockPos(9, 64, 9));
		assertEquals(1, area.chunks());
		assertEquals(0, area.minX());
		assertEquals(0, area.maxZ());
	}

	@Test
	void rectangleCountsBothDirections() {
		ChunkArea area = ChunkArea.between(new BlockPos(48, 64, 32), new BlockPos(-1, 70, 0));
		assertEquals(5, area.maxX() - area.minX() + 1);
		assertEquals(3, area.maxZ() - area.minZ() + 1);
		assertEquals(15, area.chunks());
		assertEquals(area.chunks(), ChunkArea.between(new BlockPos(-1, 70, 0), new BlockPos(48, 64, 32)).chunks());
	}

	@Test
	void limitBoundaryIsInclusive() {
		ChunkArea area = ChunkArea.between(new BlockPos(0, 64, 0), new BlockPos(31, 64, 31));
		assertEquals(4, area.chunks());
		assertFalse(area.exceeds(4));
		assertTrue(area.exceeds(3));
	}

	@Test
	void negativeCoordinatesFloorTowardsMinusInfinity() {
		ChunkArea area = ChunkArea.between(new BlockPos(-1, 64, -1), new BlockPos(-16, 64, -16));
		assertEquals(-1, area.minX());
		assertEquals(-1, area.maxX());
		assertEquals(1, area.chunks());
	}
}
