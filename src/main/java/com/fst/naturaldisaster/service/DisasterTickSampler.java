package com.fst.naturaldisaster.service;

import com.fst.naturaldisaster.field.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Deterministic coordinate selection for the disaster tick.
 *
 * <p>This is the selection stage only. It returns coordinates and never reads a block state,
 * examines a block, or applies an effect. A later disaster system resolves the block at each
 * returned position and decides what, if anything, happens there.
 *
 * <p>Every coordinate is a pure function of the world seed, the dimension, the chunk, the real
 * {@code gameTime}, a purpose label and the sample index. Draws are independent, so duplicates are
 * expected within one call and across ticks; callers must not treat the results as a set. Negative
 * ticks and negative chunk coordinates are supported without truncation.
 *
 * <p>The sampler holds no world state and nothing is persisted. A zero speed returns the shared
 * {@link #NONE} array.
 */
public final class DisasterTickSampler {
    /** Shared empty result; zero-speed calls return this exact array. */
    public static final BlockPos[] NONE = new BlockPos[0];

    // Purpose labels. They live outside DisasterField's range so a disaster-tick draw can never
    // collide with an anchor, path or event decision.
    private static final int P_SURFACE_X = 31;
    private static final int P_SURFACE_Z = 32;
    private static final int P_RANDOM_X = 33;
    private static final int P_RANDOM_Y = 34;
    private static final int P_RANDOM_Z = 35;

    private final long seed;
    private final int dimensionHash;

    public DisasterTickSampler(long seed, int dimensionHash) {
        this.seed = seed;
        this.dimensionHash = dimensionHash;
    }

    /**
     * Surface disaster tick: {@code samples} independent columns of the chunk, each a random local
     * x/z and the top block of that column read directly from the chunk's {@code WORLD_SURFACE}
     * heightmap. The caller supplies an already loaded chunk, so no per-sample chunk lookup happens.
     *
     * <p>{@link LevelChunk#getHeight(Heightmap.Types, int, int)} returns the top block itself; no
     * vertical scan and no block state query is performed.
     *
     * @param chunk  loaded chunk that owns the columns; must be a full {@link LevelChunk}
     * @param tick   real level game time
     * @param samples requested sample count; {@code <= 0} returns {@link #NONE}
     * @return a new array of exactly {@code samples} positions, duplicates allowed
     */
    public BlockPos[] surface(LevelChunk chunk, long tick, int samples) {
        if (samples <= 0) {
            return NONE;
        }
        ChunkPos chunkPos = chunk.getPos();
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        BlockPos[] positions = new BlockPos[samples];
        for (int sample = 0; sample < samples; sample++) {
            int localX = Hash.mix(seed, dimensionHash, chunkX, chunkZ, P_SURFACE_X, tick, sample) & 15;
            int localZ = Hash.mix(seed, dimensionHash, chunkX, chunkZ, P_SURFACE_Z, tick, sample) & 15;
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
            positions[sample] = new BlockPos(baseX + localX, y, baseZ + localZ);
        }
        return positions;
    }

    /**
     * Random disaster tick: {@code samples} independent positions uniformly spread over the chunk's
     * 16×16 columns and its full build height. The caller supplies an already loaded chunk, so no
     * per-sample chunk lookup happens.
     *
     * <p>No heightmap and no block state is read. The y draw uses {@link Math#floorMod(int, int)} so
     * the result is independent of hash sign and always inside {@code [minBuildHeight, height)}.
     *
     * @param chunk  loaded chunk that owns the columns; must be a full {@link LevelChunk}
     * @param tick   real level game time
     * @param samples requested sample count; {@code <= 0} returns {@link #NONE}
     * @return a new array of exactly {@code samples} positions, duplicates allowed
     */
    public BlockPos[] random(LevelChunk chunk, long tick, int samples) {
        if (samples <= 0) {
            return NONE;
        }
        ChunkPos chunkPos = chunk.getPos();
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minY = chunk.getMinBuildHeight();
        int height = chunk.getHeight();
        BlockPos[] positions = new BlockPos[samples];
        for (int sample = 0; sample < samples; sample++) {
            int localX = Hash.mix(seed, dimensionHash, chunkX, chunkZ, P_RANDOM_X, tick, sample) & 15;
            int localZ = Hash.mix(seed, dimensionHash, chunkX, chunkZ, P_RANDOM_Z, tick, sample) & 15;
            int yHash = Hash.mix(seed, dimensionHash, chunkX, chunkZ, P_RANDOM_Y, tick, sample);
            int blockY = minY + Math.floorMod(yHash, height);
            positions[sample] = new BlockPos(baseX + localX, blockY, baseZ + localZ);
        }
        return positions;
    }
}
