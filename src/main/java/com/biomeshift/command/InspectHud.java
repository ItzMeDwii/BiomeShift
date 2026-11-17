package com.biomeshift.command;

import com.biomeshift.BiomeShift;
import com.biomeshift.config.CompiledWeights;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Optional action-bar overlay: one line per second with the player's section
 * biome, top score and the would-be winner. Costs one section scan per second
 * per player who opted in.
 */
public final class InspectHud {
	private static final int PERIOD_TICKS = 20;
	private static final Set<UUID> ENABLED = new HashSet<>();
	private static long tick;

	private InspectHud() {
	}

	public static boolean toggle(ServerPlayer player) {
		UUID id = player.getUUID();
		if (ENABLED.remove(id)) return false;
		ENABLED.add(id);
		return true;
	}

	public static boolean isEnabled(ServerPlayer player) {
		return ENABLED.contains(player.getUUID());
	}

	public static void clear() {
		ENABLED.clear();
		tick = 0;
	}

	public static void tick(MinecraftServer server) {
		if (ENABLED.isEmpty()) return;
		if (++tick % PERIOD_TICKS != 0) return;

		CompiledWeights weights = BiomeShift.weights();
		if (weights == null) return;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ENABLED.contains(player.getUUID())) continue;
			InspectSnapshot snapshot = InspectSnapshot.of(player, weights);
			if (snapshot == null) continue;
			player.sendOverlayMessage(line(snapshot));
		}
	}

	private static Component line(InspectSnapshot snapshot) {
		MutableComponent head = Component.literal("BiomeShift ").withStyle(ChatFormatting.AQUA);
		Component biome = Component.literal(snapshot.currentBiome()).withStyle(ChatFormatting.WHITE);
		Component top = Component.literal(" · " + snapshot.topScores()).withStyle(ChatFormatting.GRAY);
		String traits = snapshot.rolesRequired() == 0 ? ""
				: " [" + snapshot.rolesCovered() + "/" + snapshot.rolesRequired() + "]";
		Component verdict = snapshot.changes()
				? Component.literal(" → " + snapshot.winnerBiome() + traits).withStyle(ChatFormatting.GREEN)
				: Component.literal("  (" + InspectSnapshot.format(snapshot.winnerScore()) + "/"
						+ InspectSnapshot.format(snapshot.threshold()) + traits + ")")
						.withStyle(ChatFormatting.DARK_GRAY);
		return head.append(biome).append(top).append(verdict);
	}
}
