package com.fst.naturaldisaster.service;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.field.FieldParams;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side owner of the per-level field and the loaded-chunk disaster cache.
 *
 * <p>Disasters are only evaluated for chunks that are actually loaded. A chunk is evaluated once
 * when it loads, remembered for as long as it stays loaded, and dropped on unload. The cached result
 * is tagged with the 1-second motion step; if a later query lands in a different step the chunk is
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
                    Config.INSTANCE.disasterRadiusChunks.get(),
                    Config.INSTANCE.walkSpeedBlocksPerTick.get());
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
         * <p>The cached maximum-radius candidates are valid for the entire 1-second motion step.
         * Each query applies the current event radius and real-tick state to those candidates. A
         * step change forces one re-evaluation; no global per-tick scan is needed. This method never
         * creates an entry, so unloaded chunks are never part of the disaster field.
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
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            List<CenterHit> hits = currentHits(status.hits(), tick, chunkX, chunkZ);
            return hits == status.hits() ? status : new ChunkStatus(step, hits);
        }

        /** The cache holds maximum-radius candidates so a mid-frame event start cannot be missed. */
        private ChunkStatus evaluate(long chunkKey, long tick) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            double radius = field.params().disasterRadiusChunks() * 16.0;
            double minX = chunkX * 16.0;
            double minZ = chunkZ * 16.0;
            List<CenterHit> candidates = field.centersInRect(tick,
                    minX - radius, minZ - radius, minX + 16.0 + radius, minZ + 16.0 + radius);
            List<CenterHit> hits = new ArrayList<>(candidates.size());
            for (CenterHit hit : candidates) {
                if (DisasterField.coversChunk(hit.x(), hit.z(),
                        field.params().disasterRadiusChunks(), chunkX, chunkZ)) {
                    hits.add(hit);
                }
            }
            return new ChunkStatus(field.motionStep(tick), hits);
        }

        /** Applies the real-tick event state and this frame's radius without thinning the cache. */
        private List<CenterHit> currentHits(List<CenterHit> cached, long tick, int chunkX, int chunkZ) {
            List<CenterHit> filtered = null;
            for (int index = 0; index < cached.size(); index++) {
                CenterHit hit = cached.get(index);
                EventState event = field.event(tick, hit.gridX(), hit.gridZ());
                int radius = event.active() ? event.radiusChunks() : field.params().disasterRadiusChunks();
                boolean covered = DisasterField.coversChunk(hit.x(), hit.z(), radius, chunkX, chunkZ);
                CenterHit current = radius == hit.radiusChunks() ? hit
                        : new CenterHit(hit.gridX(), hit.gridZ(), hit.x(), hit.z(), radius);
                if (filtered == null && covered && current == hit) {
                    continue;
                }
                if (filtered == null) {
                    filtered = new ArrayList<>(cached.size());
                    filtered.addAll(cached.subList(0, index));
                }
                if (covered) {
                    filtered.add(current);
                }
            }
            return filtered == null ? cached : filtered;
        }
    }

    /**
     * Cached maximum-radius candidates for one loaded chunk and motion step. {@code status} filters
     * this superset to the exact current coverage before returning it.
     *
     * @param step 1-second motion step these candidates belong to
     * @param hits maximum-radius candidates retained for the whole step
     */
    public record ChunkStatus(long step, List<CenterHit> hits) {
    }
}
