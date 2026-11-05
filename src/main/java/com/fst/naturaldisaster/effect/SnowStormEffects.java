package com.fst.naturaldisaster.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Snow storm actions only. Coverage, lifecycle and sampling budgets belong to the service. */
public final class SnowStormEffects {
    public static final int FREEZE_DURATION_TICKS = 60;
    public static final int MAX_SNOW_LAYERS = 3;

    private SnowStormEffects() {
    }

    /** Extension point for freezing; currently only Slowness I, using vanilla effect merging. */
    public static boolean freezePlayer(ServerPlayer player) {
        return DisasterEffects.applyMobEffect(player, MobEffects.MOVEMENT_SLOWDOWN, FREEZE_DURATION_TICKS, 0);
    }

    /** One sampled surface point, at most one mutation; caller has checked coverage and ticking. */
    public static void surface(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !hasLoadedNeighbours(level, pos)) {
            return;
        }
        LoadedEffectScope.run(level, () -> applySurface(level, pos));
    }

    private static void applySurface(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && SnowStormBlocks.matches(state) && state.getDestroySpeed(level, pos) >= 0) {
            level.destroyBlock(pos, true);
            return;
        }
        BlockPos above = pos.above();
        if (state.is(Blocks.WATER) && state.getFluidState().isSource() && level.canSeeSky(above)) {
            level.setBlockAndUpdate(pos, Blocks.ICE.defaultBlockState());
            return;
        }
        if (state.is(Blocks.SNOW)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers < MAX_SNOW_LAYERS && state.canSurvive(level, pos)) {
                level.setBlockAndUpdate(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1));
            }
            return;
        }
        if (!level.isOutsideBuildHeight(above) && level.getBlockState(above).isAir()) {
            BlockState snow = Blocks.SNOW.defaultBlockState();
            if (snow.canSurvive(level, above)) {
                level.setBlockAndUpdate(above, snow);
            }
        }
    }

    private static boolean hasLoadedNeighbours(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
