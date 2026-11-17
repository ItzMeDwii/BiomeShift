package com.biomeshift.command;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Optional chunk-border overlay for {@code /biomeshift inspect border}: draws the
 * 16x16 chunk column the player stands in with dust particles, once per half second.
 */
public final class BorderParticles {
	private static final Set<UUID> ENABLED = new HashSet<>();
	private static final DustParticleOptions MARK = new DustParticleOptions(0x00E5FF, 0.7f);
	private static final int PULSE_TICKS = 10;
	/** Vertical corner accent above and below the player's feet. */
	private static final int CORNER_HEIGHT = 8;
	private static long tick;

	private BorderParticles() {
	}

	public static boolean isEnabled(ServerPlayer player) {
		return ENABLED.contains(player.getUUID());
	}

	public static boolean set(ServerPlayer player, boolean on) {
		UUID id = player.getUUID();
		if (on) {
			ENABLED.add(id);
		} else {
			ENABLED.remove(id);
		}
		return on;
	}

	public static boolean toggle(ServerPlayer player) {
		return set(player, !isEnabled(player));
	}

	public static void clear() {
		ENABLED.clear();
		tick = 0;
	}

	public static void tick(MinecraftServer server) {
		if (ENABLED.isEmpty()) return;
		if (++tick % PULSE_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ENABLED.contains(player.getUUID())) draw(player);
		}
	}

	private static void draw(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		int minX = (player.getBlockX() >> 4) << 4;
		int minZ = (player.getBlockZ() >> 4) << 4;
		double y = player.getY() + 0.1;

		for (int i = 0; i <= 16; i++) {
			point(level, player, minX + i, y, minZ);
			point(level, player, minX + i, y, minZ + 16);
			point(level, player, minX, y, minZ + i);
			point(level, player, minX + 16, y, minZ + i);
		}
		for (int dy = -CORNER_HEIGHT; dy <= CORNER_HEIGHT; dy += 2) {
			double cornerY = y + dy;
			point(level, player, minX, cornerY, minZ);
			point(level, player, minX + 16, cornerY, minZ);
			point(level, player, minX, cornerY, minZ + 16);
			point(level, player, minX + 16, cornerY, minZ + 16);
		}
	}

	private static void point(ServerLevel level, ServerPlayer player, double x, double y, double z) {
		level.sendParticles(player, MARK, true, true, x + 0.5, y, z + 0.5, 1, 0, 0, 0, 0);
	}
}
