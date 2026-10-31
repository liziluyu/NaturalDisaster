package com.fst.naturaldisaster.network;

import com.fst.naturaldisaster.client.ClientDisasterEffects;
import com.fst.naturaldisaster.client.ClientDisasterState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the optional map overlay's synchronization channel and the generic client effects.
 *
 * <p>Effect payloads carry client-only handlers. The shared registration checks the physical
 * side before touching {@link ClientDisasterEffects}, while the dedicated server registers the
 * same payload types with no-op handlers so both sides agree on the protocol.
 */
public final class DisasterNetwork {
    private static final String VERSION = "4";

    private DisasterNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
                DisasterSnapshotPayload.TYPE,
                DisasterSnapshotPayload.STREAM_CODEC,
                ClientDisasterState::handle);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDisasterEffects.register(registrar);
        } else {
            registrar.playToClient(
                            ClientViewDistancePayload.TYPE,
                            ClientViewDistancePayload.STREAM_CODEC,
                            (payload, context) -> {
                            })
                    .playToClient(
                            ClientFogColorPayload.TYPE,
                            ClientFogColorPayload.STREAM_CODEC,
                            (payload, context) -> {
                            })
                    .playToClient(
                            ClientWeatherPayload.TYPE,
                            ClientWeatherPayload.STREAM_CODEC,
                            (payload, context) -> {
                            });
        }
    }
}
