package com.fst.naturaldisaster.client;

import com.fst.naturaldisaster.network.DisasterSnapshotPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable-by-replacement client view of the server's disaster chunks.
 *
 * <p>Each dimension is represented by two sorted {@code long[]} arrays: warning chunks (yellow,
 * no effect) and effect chunks (red, growing or decaying). The arrays are never mutated after
 * publication; an update builds fresh arrays and swaps in a new map, so a render pass always sees
 * a consistent snapshot without locking. The effect group wins any accidental overlap, so the two
 * arrays are always disjoint.
 */
public final class ClientDisasterState {
    private static final long[] EMPTY = new long[0];

    private static volatile Map<ResourceLocation, Snapshot> snapshots = Map.of();

    private ClientDisasterState() {
    }

    /** Payload handler: validates, deduplicates and publishes one server snapshot. */
    public static void handle(DisasterSnapshotPayload payload, IPayloadContext context) {
        long[] effect = sortedDistinct(payload.effectChunks());
        long[] warning = without(effect, sortedDistinct(payload.warningChunks()));
        ResourceLocation dimension = payload.dimension();
        context.enqueueWork(() -> accept(dimension, warning, effect));
    }

    /** Warning chunks (yellow) for a dimension, or {@code null} when the server reported none. */
    public static long[] warningChunks(ResourceKey<Level> dimension) {
        Snapshot snapshot = snapshots.get(dimension.location());
        return snapshot == null ? null : snapshot.warning;
    }

    /** Effect chunks (red) for a dimension, or {@code null} when the server reported none. */
    public static long[] effectChunks(ResourceKey<Level> dimension) {
        Snapshot snapshot = snapshots.get(dimension.location());
        return snapshot == null ? null : snapshot.effect;
    }

    /** Drops every cached snapshot; called when the client leaves a world. */
    public static void clear() {
        snapshots = Map.of();
    }

    private static long[] sortedDistinct(List<Long> chunks) {
        if (chunks.isEmpty()) {
            return EMPTY;
        }
        long[] packed = new long[chunks.size()];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = chunks.get(index);
        }
        Arrays.sort(packed);
        int unique = 1;
        for (int index = 1; index < packed.length; index++) {
            if (packed[index] != packed[unique - 1]) {
                packed[unique++] = packed[index];
            }
        }
        return unique == packed.length ? packed : Arrays.copyOf(packed, unique);
    }

    /** Removes effect chunks from the warning array; both inputs are sorted. */
    private static long[] without(long[] effect, long[] warning) {
        if (warning.length == 0 || effect.length == 0) {
            return warning;
        }
        int kept = 0;
        for (long chunk : warning) {
            if (Arrays.binarySearch(effect, chunk) < 0) {
                warning[kept++] = chunk;
            }
        }
        return kept == warning.length ? warning : Arrays.copyOf(warning, kept);
    }

    private static void accept(ResourceLocation dimension, long[] warning, long[] effect) {
        Map<ResourceLocation, Snapshot> next = new HashMap<>(snapshots);
        if (warning.length == 0 && effect.length == 0) {
            next.remove(dimension);
        } else {
            next.put(dimension, new Snapshot(warning, effect));
        }
        snapshots = Map.copyOf(next);
    }

    /** Published as a whole; callers must treat the arrays as read-only. */
    private static final class Snapshot {
        private final long[] warning;
        private final long[] effect;

        private Snapshot(long[] warning, long[] effect) {
            this.warning = warning;
            this.effect = effect;
        }
    }
}
