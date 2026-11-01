package com.fst.naturaldisaster.effect;

/**
 * Weather a client can be told to render, independently of the server's real weather.
 *
 * <p>This is a display-only value: applying it changes the client's rain/thunder render levels and
 * the precipitation type used by the client renderer, not {@code ServerLevel}'s weather cycle,
 * level data or precipitation ticking. The forced precipitation ignores the biome's temperature
 * and precipitation settings; stopping restores vanilla per-biome rendering.
 */
public enum ForcedWeather {
    /** Full rain without a thunderstorm, in every biome. */
    RAIN,
    /** Full rain plus thunderstorm intensity (darker sky, rain and thunder levels), in every biome. */
    THUNDER,
    /** Full snow rendering instead of rain, in every biome. */
    SNOW
}
