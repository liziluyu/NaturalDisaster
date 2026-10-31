package com.fst.naturaldisaster.effect;

/**
 * Weather a client can be told to render, independently of the server's real weather.
 *
 * <p>This is a display-only value: applying it changes the client's rain/thunder render levels,
 * not {@code ServerLevel}'s weather cycle, level data or precipitation ticking.
 */
public enum ForcedWeather {
    /** Full rain without a thunderstorm. */
    RAIN,
    /** Full rain plus thunderstorm intensity (darker sky, rain and thunder levels). */
    THUNDER
}
