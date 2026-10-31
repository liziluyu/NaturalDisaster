package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.effect.ForcedWeather;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-authoritative request to force or stop a client-side weather display.
 *
 * <p>The weather is a {@link ForcedWeather} enum, not a magic string, and {@code restore}
 * distinguishes "clear the override" from any particular weather value. Only the client's render
 * levels are affected; the server's weather is never sent, changed or simulated.
 *
 * @param weather the weather the client should render; meaningless when {@code restore} is true
 * @param restore whether the client should stop forcing weather and resume vanilla rendering
 */
public record ClientWeatherPayload(ForcedWeather weather, boolean restore) implements CustomPacketPayload {
    public static final Type<ClientWeatherPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "client_weather"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientWeatherPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.idMapper(id -> ForcedWeather.values()[id], ForcedWeather::ordinal),
                    ClientWeatherPayload::weather,
                    ByteBufCodecs.BOOL,
                    ClientWeatherPayload::restore,
                    ClientWeatherPayload::new);

    public static ClientWeatherPayload force(ForcedWeather weather) {
        return new ClientWeatherPayload(weather, false);
    }

    public static ClientWeatherPayload stop() {
        return new ClientWeatherPayload(ForcedWeather.RAIN, true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
