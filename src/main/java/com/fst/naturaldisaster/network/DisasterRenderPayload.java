package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A player's desired visual flag, tagged by dimension to reject stale world packets. */
public record DisasterRenderPayload(ResourceLocation dimension, boolean rendering) implements CustomPacketPayload {
    public static final Type<DisasterRenderPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "disaster_render"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DisasterRenderPayload> STREAM_CODEC =
            StreamCodec.composite(ResourceLocation.STREAM_CODEC, DisasterRenderPayload::dimension,
                    ByteBufCodecs.BOOL, DisasterRenderPayload::rendering, DisasterRenderPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
