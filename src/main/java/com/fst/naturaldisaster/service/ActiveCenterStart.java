package com.fst.naturaldisaster.service;

/**
 * Start-of-event frame of one currently active disaster center.
 *
 * <p>The start chunk is where the center stood at {@code startTick}, i.e. the historical motion
 * frame containing the tick at which the event began. It is cached only for as long as the center
 * stays active in the loaded-chunk candidates of the current tick; nothing here is persisted.
 *
 * @param gridX     fine anchor grid X
 * @param gridZ     fine anchor grid Z
 * @param startTick tick at which the event started
 * @param chunkX    block-to-chunk X of the center at {@code startTick}
 * @param chunkZ    block-to-chunk Z of the center at {@code startTick}
 */
public record ActiveCenterStart(int gridX, int gridZ, long startTick, int chunkX, int chunkZ) {
}
