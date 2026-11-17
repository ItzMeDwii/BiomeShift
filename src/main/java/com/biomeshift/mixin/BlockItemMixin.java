package com.biomeshift.mixin;

import com.biomeshift.BiomeShift;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Fabric event fires after a block is actually placed, so hook the tail of
 * {@link BlockItem#placeBlock}. Player filter also excludes dispensers, whose
 * placement context carries no player.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "placeBlock", at = @At("RETURN"))
	private void biomeshift$afterPlace(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
		if (!(context.getLevel() instanceof ServerLevel level)) return;
		if (!(context.getPlayer() instanceof ServerPlayer player)) return;
		BiomeShift.onBlockPlaced(player, level, context.getClickedPos(), state);
	}
}
