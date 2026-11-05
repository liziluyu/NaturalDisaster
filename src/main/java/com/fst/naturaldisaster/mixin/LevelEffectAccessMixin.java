package com.fst.naturaldisaster.mixin;

import com.fst.naturaldisaster.effect.LoadedEffectScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Loaded-only boundary during synchronous disaster settlement; inactive outside that scope. */
@Mixin(Level.class)
public abstract class LevelEffectAccessMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void naturaldisaster$loadedBlock(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        if (LoadedEffectScope.blocksAccess((Level) (Object) this, pos)) {
            result.setReturnValue(Blocks.VOID_AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void naturaldisaster$loadedFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        if (LoadedEffectScope.blocksAccess((Level) (Object) this, pos)) {
            result.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void naturaldisaster$loadedEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        if (LoadedEffectScope.blocksAccess((Level) (Object) this, pos)) {
            result.setReturnValue(null);
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void naturaldisaster$loadedWrite(BlockPos pos, BlockState state, int flags, int depth,
                                             CallbackInfoReturnable<Boolean> result) {
        if (LoadedEffectScope.blocksAccess((Level) (Object) this, pos)) {
            result.setReturnValue(false);
        }
    }
}
