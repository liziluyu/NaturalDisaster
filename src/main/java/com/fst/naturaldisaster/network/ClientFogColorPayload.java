package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-authoritative request to override or restore a player's fog colour.
 *
 * <p>Components are plain 0-255 RGB integers, matching the command and future disaster
 * constants; no packed colour or colour-name strings are involved. {@code restore} explicitly
 * returns the client to vanilla fog rendering.
 *
 * @param red     red component, 0-255
 * @param green   green component, 0-255
 * @param blue    blue component, 0-255
 * @param restore whether the client should stop overriding the fog colour
 */
public record ClientFogColorPayload(int red, int green, int blue, boolean restore)
        implements CustomPacketPayload {
    public static final Type<ClientFogColorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "client_fog_color"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientFogColorPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    ClientFogColorPayload::red,
                    ByteBufCodecs.VAR_INT,
                    ClientFogColorPayload::green,
                    ByteBufCodecs.VAR_INT,
                    ClientFogColorPayload::blue,
                    ByteBufCodecs.BOOL,
                    ClientFogColorPayload::restore,
                    ClientFogColorPayload::new);

    public static ClientFogColorPayload set(int red, int green, int blue) {
        return new ClientFogColorPayload(red, green, blue, false);
    }

    public static ClientFogColorPayload clear() {
        return new ClientFogColorPayload(0, 0, 0, true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
