package com.fst.naturaldisaster.service;

import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.network.DisasterSnapshotPayload;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pushes a bounded, server-authoritative snapshot of disaster chunks to each player.
 *
 * <p>For every player the snapshot covers only the chunks in that player's loaded neighbourhood
 * (view distance plus the configured maximum disaster radius). Centers are found with the pure
 * {@link DisasterField#centersInRect} query, so the cost depends on the configured density and the
 * sampled area, not on world size, elapsed time or the total number of centers. Chunks that are not
 * loaded are skipped and never force-loaded.
 *
 * <p>Each active center contributes to exactly one group: {@code WARNING} chunks are yellow and
 * have no effect, while growing/decaying ({@code effectActive}) chunks are red. The effect group
 * wins every overlap, so the two lists are disjoint on the wire and on the client. Both groups are
 * capped together by {@link DisasterSnapshotPayload#MAX_CHUNKS}; when truncation is ever needed,
 * chunks nearest the player are kept rather than whichever centers happened to be visited first.
 *
 * <p>Snapshots are sent once per second. The client provider additionally clips to the visible map
 * rectangle, so the renderer never enumerates anything beyond what the server already bounded.
 */
public final class DisasterSyncService {
    public static final DisasterSyncService INSTANCE = new DisasterSyncService();

    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final long[] NO_CHUNKS = new long[0];

    private DisasterSyncService() {
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            long tick = level.getGameTime();
            for (ServerPlayer player : players) {
                SyncChunks chunks = activeChunks(level, player, tick);
                PacketDistributor.sendToPlayer(player, new DisasterSnapshotPayload(
                        level.dimension().location(),
                        toList(chunks.warning()),
                        toList(chunks.effect())));
            }
        }
    }

    private static SyncChunks activeChunks(ServerLevel level, ServerPlayer player, long tick) {
        DisasterService.LevelState state = DisasterService.INSTANCE.state(level);
        DisasterField field = state.field();
        int maxRadiusChunks = field.params().disasterRadiusChunks();
        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        int regionRadius = viewDistance + maxRadiusChunks;

        ChunkPos playerChunk = player.chunkPosition();
        int minChunkX = playerChunk.x - regionRadius;
        int maxChunkX = playerChunk.x + regionRadius;
        int minChunkZ = playerChunk.z - regionRadius;
        int maxChunkZ = playerChunk.z + regionRadius;

        double minX = minChunkX * 16.0;
        double maxX = (maxChunkX + 1) * 16.0;
        double minZ = minChunkZ * 16.0;
        double maxZ = (maxChunkZ + 1) * 16.0;

        // Candidates are still expanded by the configured maximum radius. The actual radius of each
        // center is the event's current radius and is applied below, per center.
        double influence = maxRadiusChunks * 16.0;
        List<CenterHit> centers = field.centersInRect(tick,
                minX - influence, minZ - influence, maxX + influence, maxZ + influence);

        LongOpenHashSet warningChunks = new LongOpenHashSet();
        LongOpenHashSet effectChunks = new LongOpenHashSet();

        for (CenterHit center : centers) {
            EventState event = field.event(tick, center.gridX(), center.gridZ());
            if (!event.active()) {
                continue;
            }
            boolean effect = event.effectActive();
            if (!effect && event.phase() != EventState.Phase.WARNING) {
                continue;
            }
            int radiusChunks = event.radiusChunks();
            double centerX = center.x();
            double centerZ = center.z();
            double centerRadius = radiusChunks * 16.0;

            int centerMinX = Math.max(minChunkX, (int) Math.floor((centerX - centerRadius - 8.0) / 16.0));
            int centerMaxX = Math.min(maxChunkX, (int) Math.floor((centerX + centerRadius - 8.0) / 16.0));
            int centerMinZ = Math.max(minChunkZ, (int) Math.floor((centerZ - centerRadius - 8.0) / 16.0));
            int centerMaxZ = Math.min(maxChunkZ, (int) Math.floor((centerZ + centerRadius - 8.0) / 16.0));

            LongOpenHashSet target = effect ? effectChunks : warningChunks;
            for (int chunkX = centerMinX; chunkX <= centerMaxX; chunkX++) {
                for (int chunkZ = centerMinZ; chunkZ <= centerMaxZ; chunkZ++) {
                    if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    if (DisasterField.coversChunk(centerX, centerZ, radiusChunks, chunkX, chunkZ)) {
                        target.add(ChunkPos.asLong(chunkX, chunkZ));
                    }
                }
            }
        }

        // A chunk can be covered by several centers; the effect group always wins.
        warningChunks.removeAll(effectChunks);

        long[] effect = effectChunks.toLongArray();
        long[] warning = warningChunks.toLongArray();
        Arrays.sort(effect);
        Arrays.sort(warning);

        if (effect.length >= DisasterSnapshotPayload.MAX_CHUNKS) {
            effect = nearest(effect, DisasterSnapshotPayload.MAX_CHUNKS, playerChunk.x, playerChunk.z);
            warning = NO_CHUNKS;
        } else {
            warning = nearest(warning,
                    DisasterSnapshotPayload.MAX_CHUNKS - effect.length, playerChunk.x, playerChunk.z);
        }
        Arrays.sort(effect);
        Arrays.sort(warning);
        return new SyncChunks(warning, effect);
    }

    /** Keeps the chunks closest to the player instead of truncating in center-visit order. */
    private static long[] nearest(long[] chunks, int limit, int chunkX, int chunkZ) {
        if (chunks.length <= limit) {
            return chunks;
        }
        if (limit <= 0) {
            return NO_CHUNKS;
        }
        long[] ordered = chunks.clone();
        LongArrays.quickSort(ordered,
                (left, right) -> Double.compare(distanceSquared(left, chunkX, chunkZ),
                        distanceSquared(right, chunkX, chunkZ)));
        return Arrays.copyOf(ordered, limit);
    }

    private static double distanceSquared(long packed, int chunkX, int chunkZ) {
        double dx = ChunkPos.getX(packed) - (double) chunkX;
        double dz = ChunkPos.getZ(packed) - (double) chunkZ;
        return dx * dx + dz * dz;
    }

    private static List<Long> toList(long[] chunks) {
        if (chunks.length == 0) {
            return List.of();
        }
        List<Long> packed = new ArrayList<>(chunks.length);
        for (long chunk : chunks) {
            packed.add(chunk);
        }
        return packed;
    }

    private record SyncChunks(long[] warning, long[] effect) {
    }
}
