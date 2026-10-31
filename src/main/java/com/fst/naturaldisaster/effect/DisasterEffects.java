package com.fst.naturaldisaster.effect;

import com.fst.naturaldisaster.network.ClientFogColorPayload;
import com.fst.naturaldisaster.network.ClientViewDistancePayload;
import com.fst.naturaldisaster.network.ClientWeatherPayload;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Server-authoritative primitives shared by every disaster effect.
 *
 * <p>Disaster code only needs a {@link ServerPlayer} and the effect parameters; all client-only
 * details (custom payloads, client option restoration, fog rendering) stay behind this class.
 * Effects are applied to one player at a time and never broadcast accidentally.
 *
 * <p>View distance and fog colour are visual-only effects and travel as clientbound payloads.
 * The client remembers the view distance that was active before the first override, so repeated
 * overrides never overwrite the remembered original value; the original is restored by
 * {@link #resetViewDistance(ServerPlayer)} or when the client leaves the server. A future
 * disaster that needs a lasting effect should re-apply it while the disaster is active instead of
 * relying on a single message after a re-login.
 *
 * <p>Forced weather is display-only as well: {@link #forceWeather(ServerPlayer, ForcedWeather)}
 * makes the client render rain or a thunderstorm, and {@link #stopForcedWeather(ServerPlayer)}
 * returns the client to the weather state the server last reported. The server's own weather,
 * level data and precipitation tick are never read for, or changed by, these calls.
 */
public final class DisasterEffects {
    /** Lowest view distance accepted by the vanilla options screen. */
    public static final int MIN_VIEW_DISTANCE = 2;
    /** Highest view distance accepted by the vanilla options screen. */
    public static final int MAX_VIEW_DISTANCE = 32;
    public static final int MIN_FOG_COMPONENT = 0;
    public static final int MAX_FOG_COMPONENT = 255;

    private DisasterEffects() {
    }

    /**
     * Lowers (or raises) the target player's client render distance for as long as the override
     * remains active. The client keeps the previous value separately, so calling this repeatedly
     * with different values cannot corrupt the value restored later.
     *
     * @param chunks render distance in chunks, within [{@value #MIN_VIEW_DISTANCE},
     *               {@value #MAX_VIEW_DISTANCE}]
     */
    public static void setViewDistance(ServerPlayer player, int chunks) {
        Objects.requireNonNull(player, "player");
        if (chunks < MIN_VIEW_DISTANCE || chunks > MAX_VIEW_DISTANCE) {
            throw new IllegalArgumentException("view distance must be within ["
                    + MIN_VIEW_DISTANCE + ", " + MAX_VIEW_DISTANCE + "], got " + chunks);
        }
        PacketDistributor.sendToPlayer(player, ClientViewDistancePayload.set(chunks));
    }

    /** Restores the view distance the client had before the first override. */
    public static void resetViewDistance(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PacketDistributor.sendToPlayer(player, ClientViewDistancePayload.clear());
    }

    /**
     * Applies a vanilla mob effect through the normal server-side effect pipeline.
     *
     * @param effect        effect holder, usually obtained from the mob-effect registry
     * @param durationTicks duration in game ticks ({@link MobEffectInstance#INFINITE_DURATION}
     *                      for an infinite effect)
     * @param amplifier     0-based amplifier, within
     *                      [{@link MobEffectInstance#MIN_AMPLIFIER},
     *                      {@link MobEffectInstance#MAX_AMPLIFIER}]
     * @return whether the effect was accepted; an existing stronger effect of the same type can
     *         reject a weaker one
     */
    public static boolean applyMobEffect(ServerPlayer player, Holder<MobEffect> effect,
                                         int durationTicks, int amplifier) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(effect, "effect");
        if (durationTicks < MobEffectInstance.INFINITE_DURATION) {
            throw new IllegalArgumentException("duration must be >= "
                    + MobEffectInstance.INFINITE_DURATION + " ticks, got " + durationTicks);
        }
        if (amplifier < MobEffectInstance.MIN_AMPLIFIER || amplifier > MobEffectInstance.MAX_AMPLIFIER) {
            throw new IllegalArgumentException("amplifier must be within ["
                    + MobEffectInstance.MIN_AMPLIFIER + ", " + MobEffectInstance.MAX_AMPLIFIER
                    + "], got " + amplifier);
        }
        return player.addEffect(new MobEffectInstance(effect, durationTicks, amplifier));
    }

    /**
     * Overrides the target player's fog colour until {@link #clearFogColor(ServerPlayer)} is sent
     * or the client leaves the server. The colour is applied after vanilla biome, weather, water
     * and night-vision adjustments, so the rendered fog uses exactly these components.
     *
     * @param red   red component, 0-255
     * @param green green component, 0-255
     * @param blue  blue component, 0-255
     */
    public static void setFogColor(ServerPlayer player, int red, int green, int blue) {
        Objects.requireNonNull(player, "player");
        requireFogComponent("red", red);
        requireFogComponent("green", green);
        requireFogComponent("blue", blue);
        PacketDistributor.sendToPlayer(player, ClientFogColorPayload.set(red, green, blue));
    }

    /** Removes the fog-colour override and returns to vanilla fog rendering. */
    public static void clearFogColor(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PacketDistributor.sendToPlayer(player, ClientFogColorPayload.clear());
    }

    /**
     * Makes the target player's client render the given weather until
     * {@link #stopForcedWeather(ServerPlayer)} is sent or the client leaves the server.
     *
     * <p>Only the client's rain/thunder render levels change: the server's weather cycle, level
     * data and precipitation tick are untouched, and no rain particles or lightning are spawned on
     * the server. The client keeps rendering the forced weather while the override is active, even
     * if the server pushes an unrelated weather update; stopping restores the last weather values
     * the server reported.
     *
     * @param weather weather to display; {@link ForcedWeather#THUNDER} implies full rain as well
     */
    public static void forceWeather(ServerPlayer player, ForcedWeather weather) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(weather, "weather");
        PacketDistributor.sendToPlayer(player, ClientWeatherPayload.force(weather));
    }

    /**
     * Clears the forced weather display on the target player's client and lets vanilla weather
     * rendering resume. Idempotent when no override is active.
     */
    public static void stopForcedWeather(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        PacketDistributor.sendToPlayer(player, ClientWeatherPayload.stop());
    }

    private static void requireFogComponent(String name, int value) {
        if (value < MIN_FOG_COMPONENT || value > MAX_FOG_COMPONENT) {
            throw new IllegalArgumentException(name + " fog component must be within ["
                    + MIN_FOG_COMPONENT + ", " + MAX_FOG_COMPONENT + "], got " + value);
        }
    }
}
