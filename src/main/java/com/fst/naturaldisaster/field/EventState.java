package com.fst.naturaldisaster.field;

/**
 * Pure event-table result for one center at one tick.
 *
 * @param active    whether a disaster is running
 * @param startTick tick at which the current run started
 * @param elapsed   ticks since {@code startTick}
 * @param remaining ticks until the run ends
 */
public record EventState(boolean active, long startTick, long elapsed, long remaining) {
    public static final EventState INACTIVE = new EventState(false, 0L, 0L, 0L);
}
