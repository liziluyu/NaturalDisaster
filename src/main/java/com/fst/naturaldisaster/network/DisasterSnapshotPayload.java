package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Authoritative, bounded snapshot of the chunks covered by a disaster in one dimension.
 *
 * <p>Two disjoint groups are sent: {@code warningChunks} are in the warning phase (drawn yellow)
 * and {@code effectChunks} are in the growing or decaying phase, where the disaster actually
 * applies its effect (drawn red). The server resolves overlaps in favour of the effect group, so a
 * chunk never appears in both lists.
 *
 * <p>The server only samples chunks inside a player's loaded neighbourhood, so each list stays
 * proportional to the player's view distance plus the configured disaster radius and never to the
 * world size or the number of centers. Each list is independently bounded by {@link #MAX_CHUNKS};
 * the server additionally keeps the sum of both lists within that bound.
 *
 * @param dimension     dimension key (as a resource location) the chunks belong to
 * @param warningChunks packed {@link net.minecraft.world.level.ChunkPos} longs, warning phase
 * @param effectChunks  packed {@link net.minecraft.world.level.ChunkPos} longs, effect phase
 */
public record DisasterSnapshotPayload(ResourceLocation dimension, List<Long> warningChunks, List<Long> effectChunks)
        implements CustomPacketPayload {
    /** Upper bound on each list, and on the sum produced by the server. */
    public static final int MAX_CHUNKS = 20_000;

    public static final Type<DisasterSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "disaster_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DisasterSnapshotPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC,
                    DisasterSnapshotPayload::dimension,
                    ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(MAX_CHUNKS)),
                    DisasterSnapshotPayload::warningChunks,
                    ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(MAX_CHUNKS)),
                    DisasterSnapshotPayload::effectChunks,
                    DisasterSnapshotPayload::new);

    public DisasterSnapshotPayload {
        warningChunks = List.copyOf(warningChunks);
        effectChunks = List.copyOf(effectChunks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
