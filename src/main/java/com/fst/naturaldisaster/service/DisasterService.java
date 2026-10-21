package com.fst.naturaldisaster.service;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.FieldParams;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side owner of the per-level field and the loaded-chunk disaster cache.
 *
 * <p>Disasters are only evaluated for chunks that are actually loaded. A chunk is evaluated once
 * when it loads, remembered for as long as it stays loaded, and dropped on unload. The cached result
 * is tagged with the 10-second motion step; if a later query lands in a different step the chunk is
 * re-evaluated lazily, so no per-tick scan of loaded chunks is ever needed.
 */
public final class DisasterService {
    public static final DisasterService INSTANCE = new DisasterService();

    private final Map<ResourceKey<Level>, LevelState> levels = new HashMap<>();

    private DisasterService() {
    }

    /** Returns the level state, creating it lazily if the load event was missed. */
    public LevelState state(ServerLevel level) {
        return levels.computeIfAbsent(level.dimension(), key -> new LevelState(level));
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            levels.computeIfAbsent(level.dimension(), key -> new LevelState(level));
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            levels.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelState state = state(level);
            state.onChunkLoad(event.getChunk().getPos().toLong(), level.getGameTime());
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelState state = levels.get(level.dimension());
            if (state != null) {
                state.onChunkUnload(event.getChunk().getPos().toLong());
            }
        }
    }

    /** Per-dimension state: one immutable field snapshot plus the loaded-chunk cache. */
    public static final class LevelState {
        private final DisasterField field;
        private final Long2ObjectOpenHashMap<ChunkStatus> loadedChunks = new Long2ObjectOpenHashMap<>();

        private LevelState(ServerLevel level) {
            FieldParams params = new FieldParams(
                    Config.INSTANCE.centersPerKm2.get(),
                    Config.INSTANCE.kappa.get(),
                    Config.INSTANCE.layers.get(),
                    8,                       // coarseCells (design constant)
                    0.3,                     // coarseKappa (design constant)
                    Config.INSTANCE.disasterDurationSeconds.get() * 20,
                    Config.INSTANCE.disasterRadiusChunks.get());
            this.field = new DisasterField(
                    level.getSeed(),
                    DisasterField.dimensionHash(level.dimension().location().toString()),
                    params);
        }

        public DisasterField field() {
            return field;
        }

        void onChunkLoad(long chunkKey, long tick) {
            loadedChunks.put(chunkKey, evaluate(chunkKey, tick));
        }

        void onChunkUnload(long chunkKey) {
            loadedChunks.remove(chunkKey);
        }

        /**
         * Disaster status for a loaded chunk at {@code tick}, or {@code null} if the chunk is not
         * loaded.
         *
         * <p>The cached list is valid for the entire 10-second motion step. A step change forces one
         * re-evaluation; between steps this is a plain map lookup. Critically, this method never
         * creates an entry: evaluation only happens when a chunk loads (or an already-loaded entry
         * crosses a motion step), so unloaded chunks are never part of the disaster field.
         */
        public ChunkStatus status(long chunkKey, long tick) {
            ChunkStatus status = loadedChunks.get(chunkKey);
            if (status == null) {
                return null;
            }
            long step = field.motionStep(tick);
            if (status.step() != step) {
                status = evaluate(chunkKey, tick);
                loadedChunks.put(chunkKey, status);
            }
            return status;
        }

        private ChunkStatus evaluate(long chunkKey, long tick) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            List<CenterHit> hits = field.centersAffectingChunk(tick, chunkX, chunkZ);
            return new ChunkStatus(field.motionStep(tick), hits);
        }
    }

    /**
     * Cached coverage of one loaded chunk for one motion step.
     *
     * @param step 10-second motion step this coverage belongs to
     * @param hits centers whose influence covers the chunk
     */
    public record ChunkStatus(long step, List<CenterHit> hits) {
    }
}
