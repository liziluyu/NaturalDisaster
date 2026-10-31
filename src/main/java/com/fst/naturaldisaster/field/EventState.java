package com.fst.naturaldisaster.field;

/**
 * Pure event-table result for one center at one tick.
 *
 * <p>{@code active}, the lifecycle phase and {@code effectActive} are decided by the real tick.
 * {@code radiusChunks} is quantised to the 1-second motion frame described by
 * {@link DisasterField#MOTION_STEP_TICKS}, so it is constant within a frame.
 *
 * @param active        whether a disaster is running
 * @param startTick     tick at which the current run started
 * @param elapsed       ticks since {@code startTick}
 * @param remaining     ticks until the run ends
 * @param phase         lifecycle phase at the query tick
 * @param effectActive  whether the phase applies disaster effects
 * @param radiusChunks  influence radius in chunks at the query's motion frame
 */
public record EventState(boolean active, long startTick, long elapsed, long remaining,
                         EventState.Phase phase, boolean effectActive, int radiusChunks) {

    /** Lifecycle phase of a run. {@link #NONE} marks an inactive event. */
    public enum Phase {
        NONE,
        WARNING,
        GROWING,
        DECAYING
    }

    public static final EventState INACTIVE =
            new EventState(false, 0L, 0L, 0L, Phase.NONE, false, 0);
}
