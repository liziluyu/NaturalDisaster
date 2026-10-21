package com.fst.naturaldisaster.field;

/**
 * A center whose influence circle covers a chunk.
 *
 * @param gridX         fine anchor grid X
 * @param gridZ         fine anchor grid Z
 * @param x             center X in block coordinates
 * @param z             center Z in block coordinates
 * @param radiusChunks  influence radius in chunks (chunk-centre distance test)
 */
public record CenterHit(int gridX, int gridZ, double x, double z, int radiusChunks) {
}
