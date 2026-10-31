package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-authoritative request to override or restore a player's client render distance.
 *
 * <p>{@code restore} explicitly distinguishes "put the original value back" from "set 0 chunks";
 * the chunk count is meaningless when {@code restore} is true. The client only allows values the
 * vanilla option accepts while it holds the previous value for later restoration.
 *
 * @param chunks  requested view distance in chunks
 * @param restore whether the client should restore the value it saved before the first override
 */
public record ClientViewDistancePayload(int chunks, boolean restore) implements CustomPacketPayload {
    public static final Type<ClientViewDistancePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "client_view_distance"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientViewDistancePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    ClientViewDistancePayload::chunks,
                    ByteBufCodecs.BOOL,
                    ClientViewDistancePayload::restore,
                    ClientViewDistancePayload::new);

    public static ClientViewDistancePayload set(int chunks) {
        return new ClientViewDistancePayload(chunks, false);
    }

    public static ClientViewDistancePayload clear() {
        return new ClientViewDistancePayload(0, true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
