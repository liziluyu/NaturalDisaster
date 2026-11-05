package com.fst.naturaldisaster.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Limits synchronous terrain-effect callbacks to loaded chunks, without changing ordinary ticks. */
public final class LoadedEffectScope {
    private static final ThreadLocal<ServerLevel> CURRENT = new ThreadLocal<>();

    private LoadedEffectScope() {
    }

    public static void run(ServerLevel level, Runnable action) {
        ServerLevel previous = CURRENT.get();
        CURRENT.set(level);
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Used only by the Level mixin, including reads caused by downstream neighbour updates. */
    public static boolean blocksAccess(Level level, BlockPos pos) {
        ServerLevel scoped = CURRENT.get();
        return scoped != null && scoped == level
                && scoped.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null;
    }
}
