package com.fst.naturaldisaster.client;

import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.effect.DisasterEffects;
import com.fst.naturaldisaster.effect.ForcedWeather;
import com.fst.naturaldisaster.network.ClientFogColorPayload;
import com.fst.naturaldisaster.network.ClientViewDistancePayload;
import com.fst.naturaldisaster.network.ClientWeatherPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
 * <p>Forced weather writes the local level's rain/thunder render levels directly, which is what
 * {@code LevelRenderer}, the sky and the rain sound read. Vanilla only changes those levels when a
 * weather packet arrives, so the override is re-asserted every client tick and the last values
 * reported by the server are kept aside; stopping or disconnecting writes those values back so
 * vanilla weather resumes exactly where the server left it. In addition, a client-only mixin into
 * {@code LevelRenderer.renderSnowAndRain} asks {@link #forcedPrecipitation()} for the precipitation
 * type, so rain/thunder/snow are rendered identically in every biome instead of following the
 * biome's {@code hasPrecipitation} and temperature. No server weather, biome data or shared
 * {@code Biome} instance is changed or simulated.
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
    private static ForcedWeather forcedWeather;
    private static ClientLevel forcedLevel;
    private static float vanillaRainLevel;
    private static float vanillaThunderLevel;
    private static float appliedRainLevel;
    private static float appliedThunderLevel;

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
                        ClientDisasterEffects::handleFogColor)
                .playToClient(
                        ClientWeatherPayload.TYPE,
                        ClientWeatherPayload.STREAM_CODEC,
                        ClientDisasterEffects::handleWeather);
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

    private static void handleWeather(ClientWeatherPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.restore()) {
                stopForcedWeather();
            } else {
                forceWeather(payload.weather());
            }
        });
    }

    /**
     * Precipitation the client renderer must use while weather is forced.
     *
     * <p>Render-only accessor for the client mixin: {@code null} means no forced weather is active
     * and vanilla per-biome rules must be used. RAIN and THUNDER both render as rain; SNOW renders
     * as snow. The returned value is independent of the biome under the camera.
     */
    public static Biome.Precipitation forcedPrecipitation() {
        ForcedWeather weather = forcedWeather;
        if (weather == null) {
            return null;
        }
        return weather == ForcedWeather.SNOW ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    private static void forceWeather(ForcedWeather weather) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (forcedWeather == null || level != forcedLevel) {
            rememberVanillaWeather(level);
        }
        forcedWeather = weather;
        forcedLevel = level;
        applyForcedWeather(level, weather);
    }

    private static void stopForcedWeather() {
        ClientLevel level = Minecraft.getInstance().level;
        // Write the remembered values back only into the level that was actually overridden.
        if (level != null && level == forcedLevel) {
            level.setRainLevel(vanillaRainLevel);
            level.setThunderLevel(vanillaThunderLevel);
        }
        forcedWeather = null;
        forcedLevel = null;
    }

    private static void rememberVanillaWeather(ClientLevel level) {
        vanillaRainLevel = level.rainLevel;
        vanillaThunderLevel = level.thunderLevel;
    }

    private static void applyForcedWeather(ClientLevel level, ForcedWeather weather) {
        // Thunder is rendered as getThunderLevel() = lerp(thunder) * getRainLevel(), so a
        // thunderstorm needs full rain as well; rain and snow keep thunder at zero. All three
        // types need full rain level because renderSnowAndRain is gated on getRainLevel() > 0;
        // the rain/snow distinction itself comes from forcedPrecipitation().
        appliedRainLevel = 1.0F;
        appliedThunderLevel = weather == ForcedWeather.THUNDER ? 1.0F : 0.0F;
        // setRainLevel/setThunderLevel write both the current and the previous value, so the
        // vanilla partial-tick lerp cannot flicker between the old and the forced level.
        level.setRainLevel(appliedRainLevel);
        level.setThunderLevel(appliedThunderLevel);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ForcedWeather weather = forcedWeather;
        if (weather == null) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level != forcedLevel) {
            // A dimension change replaced the level; its weather is the new vanilla baseline.
            forcedLevel = level;
            rememberVanillaWeather(level);
        }
        // A weather packet may have written real values this tick; keep them for the stop.
        if (level.rainLevel != appliedRainLevel) {
            vanillaRainLevel = level.rainLevel;
        }
        if (level.thunderLevel != appliedThunderLevel) {
            vanillaThunderLevel = level.thunderLevel;
        }
        applyForcedWeather(level, weather);
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
        stopForcedWeather();
    }

    private record FogColor(float red, float green, float blue) {
    }
}
