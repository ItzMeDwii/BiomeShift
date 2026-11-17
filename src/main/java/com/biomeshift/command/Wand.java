package com.biomeshift.command;

import com.biomeshift.BiomeShift;
import com.biomeshift.config.CompiledWeights;
import com.biomeshift.rewrite.BiomeRewriter;
import com.biomeshift.util.ChunkArea;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Selection wand: a golden hoe tagged in NBT. Left click stores corner 1, right
 * click stores corner 2 and syncs every loaded chunk column between them to the
 * biome at corner 1. The area is capped by {@code wandMaxChunks}.
 */
public final class Wand {
	private static final String TAG = "biomeshift_wand";
	private static final Map<UUID, BlockPos> CORNERS = new HashMap<>();

	private Wand() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || level.isClientSide()) return InteractionResult.PASS;
			if (!(player instanceof ServerPlayer serverPlayer) || !isWand(player.getMainHandItem())) {
				return InteractionResult.PASS;
			}
			secondCorner(serverPlayer, hit.getBlockPos());
			return InteractionResult.SUCCESS;
		});
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (hand != InteractionHand.MAIN_HAND || level.isClientSide()) return InteractionResult.PASS;
			if (!(player instanceof ServerPlayer serverPlayer) || !isWand(player.getMainHandItem())) {
				return InteractionResult.PASS;
			}
			CORNERS.put(serverPlayer.getUUID(), pos.immutable());
			serverPlayer.sendSystemMessage(Component.literal("BiomeShift wand: corner 1 at "
					+ pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
					+ " — right click corner 2").withStyle(ChatFormatting.AQUA));
			return InteractionResult.SUCCESS;
		});
	}

	public static void give(ServerPlayer player) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		ItemStack stack = new ItemStack(Items.GOLDEN_HOE);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				Component.literal("BiomeShift Wand").withStyle(ChatFormatting.AQUA));
		stack.set(net.minecraft.core.component.DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Left click: corner 1").withStyle(ChatFormatting.GRAY),
				Component.literal("Right click: corner 2, then sync").withStyle(ChatFormatting.GRAY))));
		player.getInventory().add(stack);
		player.sendSystemMessage(Component.literal("BiomeShift wand given")
				.withStyle(ChatFormatting.GREEN));
	}

	public static void clear() {
		CORNERS.clear();
	}

	private static boolean isWand(ItemStack stack) {
		CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(TAG, false);
	}

	private static void secondCorner(ServerPlayer player, BlockPos second) {
		CompiledWeights weights = BiomeShift.weights();
		if (weights == null) {
			player.sendSystemMessage(Component.literal("BiomeShift: config not loaded")
					.withStyle(ChatFormatting.RED));
			return;
		}
		BlockPos first = CORNERS.get(player.getUUID());
		if (first == null) {
			player.sendSystemMessage(Component.literal("BiomeShift wand: left-click corner 1 first")
					.withStyle(ChatFormatting.YELLOW));
			return;
		}
		apply(player, weights, first, second);
		CORNERS.remove(player.getUUID());
	}

	private static void apply(ServerPlayer player, CompiledWeights weights, BlockPos first, BlockPos second) {
		ServerLevel level = (ServerLevel) player.level();
		ChunkArea area = ChunkArea.between(first, second);
		if (area.exceeds(weights.wandMaxChunks())) {
			player.sendSystemMessage(Component.literal("BiomeShift wand: " + area.chunks()
					+ " chunks selected, limit is " + weights.wandMaxChunks()
					+ " (config wandMaxChunks)").withStyle(ChatFormatting.RED));
			return;
		}

		LevelChunk source = level.getChunkSource().getChunkNow(first.getX() >> 4, first.getZ() >> 4);
		if (source == null) {
			player.sendSystemMessage(Component.literal("BiomeShift wand: corner 1 chunk is not loaded")
					.withStyle(ChatFormatting.RED));
			return;
		}
		int sectionIndex = source.getSectionIndex(first.getY());
		LevelChunkSection[] sourceSections = source.getSections();
		if (sectionIndex < 0 || sectionIndex >= sourceSections.length || sourceSections[sectionIndex] == null) {
			player.sendSystemMessage(Component.literal("BiomeShift wand: corner 1 section not available")
					.withStyle(ChatFormatting.RED));
			return;
		}
		Holder<Biome> target = sourceSections[sectionIndex].getNoiseBiome(
				(first.getX() & 15) >> 2, (first.getY() & 15) >> 2, (first.getZ() & 15) >> 2);

		List<LevelChunk> written = new ArrayList<>();
		int skipped = 0;
		for (int chunkX = area.minX(); chunkX <= area.maxX(); chunkX++) {
			for (int chunkZ = area.minZ(); chunkZ <= area.maxZ(); chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					skipped++;
					continue;
				}
				int top = chunk.getSections().length - 1;
				if (BiomeRewriter.applyRange(chunk, 0, top, target)) written.add(chunk);
			}
		}
		if (!written.isEmpty()) BiomeRewriter.resendAll(level, written);

		int targetIndex = weights.indexOf(target);
		String name = targetIndex >= 0 ? weights.nameOf(targetIndex) : String.valueOf(target.unwrapKey());
		player.sendSystemMessage(Component.literal("BiomeShift wand: synced " + written.size()
				+ " chunk columns to " + name
				+ (skipped > 0 ? " (" + skipped + " unloaded skipped)" : ""))
				.withStyle(ChatFormatting.GREEN));
	}
}
