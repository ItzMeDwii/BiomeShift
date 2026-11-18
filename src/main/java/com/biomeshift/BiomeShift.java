package com.biomeshift;

import com.biomeshift.command.BorderParticles;
import com.biomeshift.command.InspectHud;
import com.biomeshift.command.Wand;
import com.biomeshift.command.ShiftCommand;
import com.biomeshift.config.CompiledWeights;
import com.biomeshift.config.ConfigStore;
import com.biomeshift.trigger.PendingSections;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * BiomeShift entrypoint. Server-only: everything happens on the logical server,
 * vanilla clients receive ordinary chunk-biome packets.
 */
public final class BiomeShift implements ModInitializer {
	public static final String MOD_ID = "biomeshift";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static volatile CompiledWeights weights;
	/** Command override; {@code null} follows the config {@code enabled} value. */
	private static volatile Boolean enabledOverride;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(BiomeShift::reload);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			weights = null;
			enabledOverride = null;
			PendingSections.clear();
			InspectHud.clear();
			BorderParticles.clear();
			Wand.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(PendingSections::tick);
		ServerTickEvents.END_SERVER_TICK.register(InspectHud::tick);
		ServerTickEvents.END_SERVER_TICK.register(BorderParticles::tick);
		Wand.register();
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> ShiftCommand.register(dispatcher));
		LOG.info("[{}] initialised", MOD_ID);
	}

	/** Called from the block-placement mixin after a successful player placement. */
	public static void onBlockPlaced(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		CompiledWeights current = weights;
		if (current == null || !enabled()) return;
		if (!current.allows(level.dimension())) return;
		if (!current.isMapped(state.getBlock())) return;

		long section = SectionPos.asLong(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getY()),
				SectionPos.blockToSectionCoord(pos.getZ()));
		PendingSections.mark(level.dimension(), section, pos);

		if (isGrowthMarker(state)) {
			PendingSections.markWarm(level.dimension(), section, pos, state.getBlock(),
					current.rescanWindowTicks(), current.rescanIntervalTicks());
		}
	}

	/**
	 * Tree growth (bone meal or random tick) goes through feature placement, not
	 * BlockItem#placeBlock, so it never fires our trigger. Planting one of these
	 * blocks keeps the section under a cheap poll so the grown tree gets scored.
	 */
	private static boolean isGrowthMarker(BlockState state) {
		return state.is(BlockTags.SAPLINGS) || state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS);
	}

	public static void reload(MinecraftServer server) {
		enabledOverride = null;
		try {
			weights = CompiledWeights.load(server);
			LOG.info("[{}] config loaded: {} biomes, {} blocks, threshold {}, mode {}, enabled {}",
					MOD_ID, weights.biomeCount(), weights.blockCount(), weights.threshold(), weights.mode(), weights.enabled());
		} catch (RuntimeException e) {
			weights = null;
			LOG.error("[{}] config failed to load; mod disabled", MOD_ID, e);
		}
	}

	/**
	 * Effective switch for the automatic trigger: the command override when one is
	 * set, the config {@code enabled} value otherwise. Manual tools ({@code sync},
	 * {@code wand}) stay available while this is off.
	 */
	public static boolean enabled() {
		Boolean override = enabledOverride;
		if (override != null) return override;
		CompiledWeights current = weights;
		return current != null && current.enabled();
	}

	/**
	 * Switches the automatic trigger and writes {@code enabled} back to the config
	 * file, so the choice survives a restart. Turning it off drops queued scans so
	 * nothing flips after the switch.
	 *
	 * @return false when the config file could not be updated; the override still applies
	 */
	public static boolean setEnabled(boolean value) {
		enabledOverride = value;
		if (!value) PendingSections.dropQueued();
		boolean persisted = persistEnabled(value);
		LOG.info("[{}] automatic shifting {} ({})", MOD_ID,
				value ? "enabled" : "disabled",
				persisted ? "saved to config" : "runtime only");
		return persisted;
	}

	private static boolean persistEnabled(boolean value) {
		try {
			return ConfigStore.saveEnabled(value);
		} catch (IOException | RuntimeException e) {
			LOG.warn("[{}] could not write enabled={} to {}; runtime only", MOD_ID, value, ConfigStore.PATH, e);
			return false;
		}
	}

	public static CompiledWeights weights() {
		return weights;
	}
}
