package com.fst.naturaldisaster.service;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.disaster.BiomeDisasterTypes;
import com.fst.naturaldisaster.disaster.DisasterType;
import com.fst.naturaldisaster.disaster.DisasterTypes;
import com.fst.naturaldisaster.effect.DisasterEffectDispatcher;
import com.fst.naturaldisaster.effect.SnowStormEffects;
import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.CenterPosition;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.field.FieldParams;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

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
 * re-evaluated lazily.
 *
 * <p>Every server tick the cached loaded chunks are visited once for the disaster tick: every
 * active center found among their candidates maintains an {@link ActiveCenterStart} entry, and a
 * chunk whose current coverage includes at least one {@link EventState#effectActive()} center
 * selects surface and random coordinates. Snow storms consume the surface selection only in
 * block-ticking chunks; player effects are aggregated afterwards. The existing field alone owns
 * event timing, lifecycle and coverage. The chunk cache, not a world scan, supplies visited chunks.
 */
public final class DisasterService {
    public static final DisasterService INSTANCE = new DisasterService();

    private final Map<ResourceKey<Level>, LevelState> levels = new HashMap<>();
    private final DisasterEffectDispatcher effects = new DisasterEffectDispatcher();

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

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        for (LevelState state : levels.values()) {
            state.disasterTick();
        }
        effects.tick(event.getServer(), player -> {
            LevelState state = levels.get(player.level().dimension());
            return state != null && state.snowStormChunks.contains(player.chunkPosition().toLong());
        });
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        levels.clear();
        effects.clear();
    }

    /**
     * Per-dimension state: one immutable field snapshot, the loaded-chunk cache, and the
     * active-center start-chunk cache maintained once per tick.
     */
    public static final class LevelState {
        private final ServerLevel level;
        private final DisasterField field;
        private final DisasterTickSampler sampler;
        private final Long2ObjectOpenHashMap<ChunkStatus> loadedChunks = new Long2ObjectOpenHashMap<>();
        /** Start chunk of every center active in this tick's loaded candidates. */
        private final Long2ObjectOpenHashMap<ActiveCenterStart> startChunks = new Long2ObjectOpenHashMap<>();
        /** Center ids active in this tick's loaded candidates; prunes {@link #startChunks} afterwards. */
        private final LongOpenHashSet activeCenterIds = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<DisasterType> centerTypes = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet snowStormChunks = new LongOpenHashSet();

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
            long seed = level.getSeed();
            int dimensionHash = DisasterField.dimensionHash(level.dimension().location().toString());
            this.level = level;
            this.field = new DisasterField(seed, dimensionHash, params);
            this.sampler = new DisasterTickSampler(seed, dimensionHash);
        }

        public DisasterField field() {
            return field;
        }

        /**
         * Start chunk of the active center at fine grid cell {@code (gridX, gridZ)}, or {@code null}
         * when that center is not active in this tick's loaded-chunk candidates.
         */
        public ActiveCenterStart activeCenterStart(int gridX, int gridZ) {
            return startChunks.get(DisasterField.centerId(gridX, gridZ));
        }

        /** Immutable snapshot of every active center's start chunk maintained for this tick. */
        public List<ActiveCenterStart> activeCenterStarts() {
            return startChunks.isEmpty() ? List.of() : List.copyOf(startChunks.values());
        }

        /**
         * Type for an event's start frame, including centers outside the loaded candidates.
         * Reuses a matching derived entry when present; otherwise computes without loading chunks
         * or adding entries to the active-center caches. The caller supplies the field's startTick.
         */
        public DisasterType typeForEvent(int gridX, int gridZ, long startTick) {
            long id = DisasterField.centerId(gridX, gridZ);
            ActiveCenterStart cached = startChunks.get(id);
            if (cached != null && cached.startTick() == startTick) {
                DisasterType type = centerTypes.get(id);
                if (type != null) {
                    return type;
                }
            }
            return resolveType(startChunk(gridX, gridZ, startTick));
        }

        void onChunkLoad(long chunkKey, long tick) {
            loadedChunks.put(chunkKey, evaluate(chunkKey, tick));
        }

        void onChunkUnload(long chunkKey) {
            loadedChunks.remove(chunkKey);
        }

        /**
         * Visits this tick's loaded-chunk candidates once: maintains the active-center start-chunk
         * cache and, when at least one disaster tick speed is non-zero, samples the covered chunks.
         *
         * <p>Both speeds are read once per level and tick. The loop walks the loaded-chunk cache, so
         * unloaded chunks are never visited, nothing is force-loaded, and no world scan happens. A
         * qualifying chunk is resolved to one {@link LevelChunk} through a non-loading chunk-source
         * call and then sampled. Snow storm surface actions run only in block-ticking chunks and
         * enforce their own loaded-only access boundary; random selection has no effect yet.
         * Selection results are never cached or persisted.
         *
         * <p>The active-center bookkeeping runs even when both speeds are zero; only sampling is
         * skipped. Start-chunk entries that are not in this tick's active set are dropped at the end
         * of the tick, so the cache never outlives the event or the loaded candidates.
         */
        void disasterTick() {
            long tick = level.getGameTime();
            int surfaceSpeed = Config.INSTANCE.surfaceDisasterTickSpeed.get();
            int randomSpeed = Config.INSTANCE.randomDisasterTickSpeed.get();
            boolean sample = surfaceSpeed != 0 || randomSpeed != 0;

            activeCenterIds.clear();
            snowStormChunks.clear();
            LongIterator chunks = loadedChunks.keySet().iterator();
            while (chunks.hasNext()) {
                long chunkKey = chunks.nextLong();
                if (!visitEffectCenters(chunkKey, tick) || !sample) {
                    continue;
                }
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                var surface = sampler.surface(chunk, tick, surfaceSpeed);
                if (snowStormChunks.contains(chunkKey) && level.getChunkSource().isPositionTicking(chunkKey)) {
                    for (var pos : surface) {
                        SnowStormEffects.surface(level, pos);
                    }
                }
                sampler.random(chunk, tick, randomSpeed);
            }
            pruneStartChunks();
        }

        /**
         * Whether at least one effect-active center covers this loaded chunk right now, while also
         * recording every running center among the candidates.
         *
         * <p>The candidate loop never stops at the first effect match: the full walk is what yields
         * the complete active-center set for this tick. Every candidate whose event is running
         * (including {@link EventState.Phase#WARNING}) is recorded once in
         * {@link #activeCenterIds}, and its start chunk is computed at most once even when several
         * loaded chunks reference the same center. The returned boolean keeps the sampling gate
         * unchanged: it is set only for an effect-active center whose current radius covers the
         * chunk.
         *
         * <p>This reads the cached maximum-radius candidates directly and applies the current event
         * radius. It deliberately does not call {@link #status(long, long)} or {@code currentHits}:
         * the hot path must not allocate a filtered list or a new {@link ChunkStatus}. The public
         * query's filtering semantics are unchanged.
         */
        private boolean visitEffectCenters(long chunkKey, long tick) {
            ChunkStatus status = loadedChunks.get(chunkKey);
            if (status == null) {
                return false;
            }
            long step = field.motionStep(tick);
            if (status.step() != step) {
                status = evaluate(chunkKey, tick);
                loadedChunks.put(chunkKey, status);
            }
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            List<CenterHit> hits = status.hits();
            boolean affected = false;
            for (int index = 0; index < hits.size(); index++) {
                CenterHit hit = hits.get(index);
                EventState event = field.event(tick, hit.gridX(), hit.gridZ());
                if (!event.active()) {
                    continue;
                }
                trackStartChunk(hit.gridX(), hit.gridZ(), event.startTick());
                if (event.effectActive()
                        && DisasterField.coversChunk(hit.x(), hit.z(), event.radiusChunks(), chunkX, chunkZ)) {
                    affected = true;
                    if (DisasterTypes.SNOW_STORM.equals(centerTypes.get(DisasterField.centerId(hit.gridX(), hit.gridZ())))) {
                        snowStormChunks.add(chunkKey);
                    }
                }
            }
            return affected;
        }

        /**
         * Records one active center for this tick and keeps its start-chunk entry.
         *
         * <p>The historical frame position is recomputed only when the center has no entry yet or
         * its event restarted with a different {@code startTick}; repeated encounters within the
         * same tick are free. The query never touches the current motion-frame caches.
         */
        private void trackStartChunk(int gridX, int gridZ, long startTick) {
            long centerId = DisasterField.centerId(gridX, gridZ);
            activeCenterIds.add(centerId);
            ActiveCenterStart cached = startChunks.get(centerId);
            if (cached != null && cached.startTick() == startTick) {
                return;
            }
            startChunks.put(centerId, startChunk(gridX, gridZ, startTick));
            centerTypes.put(centerId, resolveType(startChunks.get(centerId)));
        }

        private ActiveCenterStart startChunk(int gridX, int gridZ, long startTick) {
            CenterPosition position = field.centerPositionUncached(startTick, gridX, gridZ);
            return new ActiveCenterStart(gridX, gridZ, startTick,
                    (int) Math.floor(position.x() / 16.0),
                    (int) Math.floor(position.z() / 16.0));
        }

        /** Generated surface biome: no chunk lookup, so unloaded starts and reloads agree. */
        private DisasterType resolveType(ActiveCenterStart start) {
            int x = start.chunkX() * 16 + 8;
            int z = start.chunkZ() * 16 + 8;
            var generator = level.getChunkSource().getGenerator();
            var randomState = level.getChunkSource().randomState();
            int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
            y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
            return BiomeDisasterTypes.typeFor(generator.getBiomeSource().getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler()));
        }

        /** Drops start-chunk entries of centers that left this tick's active set. */
        private void pruneStartChunks() {
            if (!startChunks.isEmpty()) {
                startChunks.keySet().retainAll(activeCenterIds);
                centerTypes.keySet().retainAll(activeCenterIds);
            }
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
