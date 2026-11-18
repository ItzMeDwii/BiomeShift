package com.biomeshift.command;

import com.biomeshift.BiomeShift;
import com.biomeshift.config.CompiledWeights;
import com.biomeshift.rewrite.BiomeRewriter;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ShiftCommand {
	private ShiftCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("biomeshift")
				.then(Commands.literal("reload")
						.requires(PermissionPredicates.require(Perms.COMMAND_RELOAD, Perms.FALLBACK))
						.executes(context -> {
							BiomeShift.reload(context.getSource().getServer());
							context.getSource().sendSuccess(
									() -> Component.literal("BiomeShift config reloaded (automatic shifting "
											+ (BiomeShift.enabled() ? "on" : "off") + ")"), true);
							return 1;
						}))
				.then(Commands.literal("on")
						.requires(PermissionPredicates.require(Perms.COMMAND_TOGGLE, Perms.FALLBACK))
						.executes(context -> toggle(context.getSource(), true)))
				.then(Commands.literal("off")
						.requires(PermissionPredicates.require(Perms.COMMAND_TOGGLE, Perms.FALLBACK))
						.executes(context -> toggle(context.getSource(), false)))
				.then(Commands.literal("toggle")
						.requires(PermissionPredicates.require(Perms.COMMAND_TOGGLE, Perms.FALLBACK))
						.executes(context -> toggle(context.getSource(), !BiomeShift.enabled())))
				.then(Commands.literal("status")
						.requires(PermissionPredicates.require(Perms.COMMAND_STATUS, Perms.FALLBACK))
						.executes(context -> {
							var weights = BiomeShift.weights();
							String text = weights == null
									? "BiomeShift: disabled (config failed to load)"
									: "BiomeShift: " + weights.biomeCount() + " biomes, "
											+ weights.blockCount() + " blocks, mode " + weights.mode()
											+ ", threshold " + weights.threshold()
											+ ", automatic shifting " + (BiomeShift.enabled() ? "on" : "off");
							context.getSource().sendSuccess(() -> Component.literal(text), false);
							return 1;
						}))
				.then(Commands.literal("inspect")
						.requires(PermissionPredicates.require(Perms.COMMAND_INSPECT, Perms.FALLBACK))
						.executes(context -> inspect(context.getSource()))
						.then(Commands.literal("on")
								.executes(context -> hud(context.getSource(), true)))
						.then(Commands.literal("off")
								.executes(context -> hud(context.getSource(), false)))
						.then(Commands.literal("border")
								.executes(context -> border(context.getSource(), null))
								.then(Commands.literal("on")
										.executes(context -> border(context.getSource(), true)))
								.then(Commands.literal("off")
										.executes(context -> border(context.getSource(), false)))))
				.then(Commands.literal("wand")
						.requires(PermissionPredicates.require(Perms.COMMAND_WAND, Perms.FALLBACK))
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayer();
							if (player == null) {
								context.getSource().sendFailure(
										Component.literal("BiomeShift: wand must be given to a player"));
								return 0;
							}
							Wand.give(player);
							return 1;
						}))
				.then(Commands.literal("sync")
						.requires(PermissionPredicates.require(Perms.COMMAND_INSPECT, Perms.FALLBACK))
						.executes(context -> syncColumn(context.getSource()))));
	}

	/**
	 * Switches the automatic trigger and persists {@code enabled} to the config
	 * file. Manual tools ({@code sync}, {@code wand}) keep working.
	 */
	private static int toggle(CommandSourceStack source, boolean on) {
		if (on && BiomeShift.weights() == null) {
			source.sendFailure(Component.literal("BiomeShift: config not loaded; fix it and run /biomeshift reload"));
			return 0;
		}
		boolean persisted = BiomeShift.setEnabled(on);
		source.sendSuccess(() -> Component.literal("BiomeShift automatic shifting ")
				.append(Component.literal(on ? "enabled" : "disabled")
						.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(Component.literal(persisted
						? " (saved to config/biomeshift.json)"
						: " for this server run only (config could not be written, see log)")
						.withStyle(ChatFormatting.GRAY)), true);
		return 1;
	}

	/** Chat snapshot of the section the executing player stands in. */
	private static int inspect(CommandSourceStack source) {
		CompiledWeights weights = BiomeShift.weights();
		if (weights == null) {
			source.sendFailure(Component.literal("BiomeShift: config not loaded"));
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("BiomeShift: /biomeshift inspect must be run by a player"));
			return 0;
		}
		InspectSnapshot snapshot = InspectSnapshot.of(player, weights);
		if (snapshot == null) {
			source.sendFailure(Component.literal("BiomeShift: section not available at this position"));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("BiomeShift ")
				.withStyle(ChatFormatting.AQUA)
				.append(Component.literal("section [" + snapshot.sectionX() + ", " + snapshot.sectionY() + ", "
						+ snapshot.sectionZ() + "] · chunk " + snapshot.chunkX() + ", " + snapshot.chunkZ())
						.withStyle(ChatFormatting.GRAY)), false);

		source.sendSuccess(() -> Component.literal("  current  ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(snapshot.currentBiome())
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  score " + InspectSnapshot.format(snapshot.currentScore()))
						.withStyle(ChatFormatting.GRAY)), false);

		source.sendSuccess(() -> Component.literal("  top      ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(snapshot.topScores()).withStyle(ChatFormatting.WHITE)), false);

		source.sendSuccess(() -> Component.literal("  built    ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(snapshot.mappedBlocks() + " mapped blocks of "
						+ snapshot.nonAirBlocks() + " non-air in 4096").withStyle(ChatFormatting.WHITE)), false);

		String traitText = snapshot.traitsBiome().isEmpty()
				? "no scored target yet"
				: snapshot.rolesRequired() == 0
				? "no trait requirements for this target"
				: snapshot.traitsBiome() + " " + snapshot.rolesCovered() + "/" + snapshot.rolesRequired()
						+ (snapshot.missingRoles().isEmpty() ? " (complete)" : " (missing " + snapshot.missingRoles() + ")");
		source.sendSuccess(() -> Component.literal("  traits   ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(traitText)
						.withStyle(snapshot.eligible() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)), false);

		String verdict = snapshot.changes()
				? "would become " + snapshot.winnerBiome() + " (score "
						+ InspectSnapshot.format(snapshot.winnerScore()) + ")"
				: "no change (best " + InspectSnapshot.format(snapshot.winnerScore()) + ", need "
						+ InspectSnapshot.format(snapshot.threshold()) + " and +"
						+ InspectSnapshot.format(snapshot.hysteresis()) + " over current"
						+ (snapshot.eligible() || snapshot.traitsBiome().isEmpty() ? "" : "; traits incomplete") + ")";
		source.sendSuccess(() -> Component.literal("  verdict  ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(verdict)
						.withStyle(snapshot.changes() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)), false);
		return 1;
	}

	/** Rewrites the whole chunk column to the biome of the section the player stands in. */
	private static int syncColumn(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("BiomeShift: sync must be run by a player"));
			return 0;
		}
		CompiledWeights weights = BiomeShift.weights();
		if (weights == null) {
			source.sendFailure(Component.literal("BiomeShift: config not loaded"));
			return 0;
		}
		ServerLevel level = (ServerLevel) player.level();
		LevelChunk chunk = level.getChunkSource().getChunkNow(
				SectionPos.blockToSectionCoord(player.getBlockX()),
				SectionPos.blockToSectionCoord(player.getBlockZ()));
		if (chunk == null) {
			source.sendFailure(Component.literal("BiomeShift: chunk not loaded"));
			return 0;
		}
		int sectionIndex = chunk.getSectionIndex(player.getBlockY());
		LevelChunkSection[] sections = chunk.getSections();
		if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) {
			source.sendFailure(Component.literal("BiomeShift: section not available"));
			return 0;
		}
		BlockPos pos = player.blockPosition();
		Holder<Biome> target = sections[sectionIndex].getNoiseBiome(
				SectionPos.sectionRelative(pos.getX()) >> 2,
				SectionPos.sectionRelative(pos.getY()) >> 2,
				SectionPos.sectionRelative(pos.getZ()) >> 2);
		if (!BiomeRewriter.applyRange(chunk, 0, sections.length - 1, target)) {
			source.sendFailure(Component.literal("BiomeShift: column not writable"));
			return 0;
		}
		BiomeRewriter.resend(level, chunk);
		String name = weights.indexOf(target) >= 0
				? weights.nameOf(weights.indexOf(target))
				: String.valueOf(target.unwrapKey().orElse(null));
		source.sendSuccess(() -> Component.literal("BiomeShift column set to " + name)
				.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	/** Toggles the chunk-border particle overlay; a bare {@code border} flips it. */
	private static int border(CommandSourceStack source, Boolean wantOn) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("BiomeShift: the chunk border must be toggled by a player"));
			return 0;
		}
		boolean state = wantOn == null ? BorderParticles.toggle(player) : BorderParticles.set(player, wantOn);
		source.sendSuccess(() -> Component.literal("BiomeShift chunk border " + (state ? "on" : "off"))
				.withStyle(state ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		return 1;
	}

	/** Toggles the action-bar overlay for the executing player. */
	private static int hud(CommandSourceStack source, boolean wantOn) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("BiomeShift: the HUD must be toggled by a player"));
			return 0;
		}
		boolean enabled = InspectHud.isEnabled(player);
		if (enabled != wantOn) enabled = InspectHud.toggle(player);
		final boolean state = enabled;
		source.sendSuccess(() -> Component.literal("BiomeShift HUD " + (state ? "on" : "off"))
				.withStyle(state ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		return 1;
	}
}
