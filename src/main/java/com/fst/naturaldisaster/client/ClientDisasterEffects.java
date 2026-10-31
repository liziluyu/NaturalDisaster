package com.fst.naturaldisaster.client;

import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.effect.DisasterEffects;
import com.fst.naturaldisaster.network.ClientFogColorPayload;
import com.fst.naturaldisaster.network.ClientViewDistancePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client half of the generic disaster effects.
 *
 * <p>View distance overrides remember the value that was active before the first override, so
 * repeated overrides cannot overwrite the remembered original. The original is written back (and
 * saved to {@code options.txt}) on explicit reset and on disconnect, which keeps a debug effect
 * from permanently changing the player's settings. Fog colour is a volatile render-time value and
 * is dropped on disconnect; nothing is persisted.
 *
 * <p>This class is only loaded on the client. The shared network registration calls
 * {@link #register(PayloadRegistrar)} behind a dist check.
 */
@EventBusSubscriber(modid = NaturalDisaster.MODID, value = Dist.CLIENT)
public final class ClientDisasterEffects {
    private static final int NO_ORIGINAL = -1;
    private static final int MIN_CHANNEL = 0;
    private static final int MAX_CHANNEL = 255;

    private static int originalViewDistance = NO_ORIGINAL;
    private static volatile FogColor fogColor;

    private ClientDisasterEffects() {
    }

    /** Registers the clientbound effect payload handlers; called only on the client. */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                        ClientViewDistancePayload.TYPE,
                        ClientViewDistancePayload.STREAM_CODEC,
                        ClientDisasterEffects::handleViewDistance)
                .playToClient(
                        ClientFogColorPayload.TYPE,
                        ClientFogColorPayload.STREAM_CODEC,
                        ClientDisasterEffects::handleFogColor);
    }

    private static void handleViewDistance(ClientViewDistancePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.restore()) {
                restoreViewDistance();
            } else {
                applyViewDistance(payload.chunks());
            }
        });
    }

    private static void handleFogColor(ClientFogColorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.restore()) {
                fogColor = null;
            } else {
                fogColor = new FogColor(
                        channel(payload.red()),
                        channel(payload.green()),
                        channel(payload.blue()));
            }
        });
    }

    private static float channel(int value) {
        return Mth.clamp(value, MIN_CHANNEL, MAX_CHANNEL) / (float) MAX_CHANNEL;
    }

    private static void applyViewDistance(int chunks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (originalViewDistance == NO_ORIGINAL) {
            originalViewDistance = minecraft.options.renderDistance().get();
        }
        OptionInstance<Integer> option = minecraft.options.renderDistance();
        int requested = Mth.clamp(chunks, DisasterEffects.MIN_VIEW_DISTANCE, DisasterEffects.MAX_VIEW_DISTANCE);
        // The option's own maximum depends on the client's available memory; never set a rejected value.
        option.set(option.values().validateValue(requested).orElse(option.get()));
        minecraft.options.broadcastOptions();
    }

    private static void restoreViewDistance() {
        if (originalViewDistance == NO_ORIGINAL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.renderDistance().set(originalViewDistance);
        originalViewDistance = NO_ORIGINAL;
        minecraft.options.broadcastOptions();
        // A save may have happened while the override was active; persist the restored value too.
        minecraft.options.save();
    }

    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        FogColor color = fogColor;
        if (color != null) {
            event.setRed(color.red());
            event.setGreen(color.green());
            event.setBlue(color.blue());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        restoreViewDistance();
        fogColor = null;
    }

    private record FogColor(float red, float green, float blue) {
    }
}
