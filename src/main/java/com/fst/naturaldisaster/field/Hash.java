package com.fst.naturaldisaster.field;

/**
 * 32-bit splitmix/Murmur-style mixing used by every deterministic decision in the field.
 *
 * <p>Each caller passes a distinct purpose label. Never reuse one random value for unrelated
 * purposes: anchor jitter, home radius, path phase, waypoint phases and event slots all have their
 * own label so that changing one feature does not reshuffle the others.
 *
 * <p>All operations are plain integer arithmetic and therefore bit-identical across JVMs.
 */
public final class Hash {
    private Hash() {
    }

    /** Final avalanche stage of MurmurHash3. */
    static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** Domain-separated mix of seed, dimension, grid cell and purpose. */
    static int mix(long seed, int dimension, int i, int j, int purpose) {
        int h = 0x9E3779B9;
        h = mix(h ^ (int) seed);
        h = mix(h ^ (int) (seed >>> 32));
        h = mix(h ^ dimension);
        h = mix(h ^ i);
        h = mix(h ^ j);
        h = mix(h ^ purpose);
        return h;
    }

    /** Variant for time-indexed decisions (event slots). */
    static int mix(long seed, int dimension, int i, int j, int purpose, long slot) {
        int h = mix(seed, dimension, i, j, purpose);
        h = mix(h ^ (int) slot);
        h = mix(h ^ (int) (slot >>> 32));
        return h;
    }

    /**
     * Variant for per-tick coordinate draws: one independent value per purpose and sample index.
     *
     * <p>This is an overload, so the existing time-indexed outputs are unchanged.
     */
    public static int mix(long seed, int dimension, int i, int j, int purpose, long slot, int sample) {
        int h = mix(seed, dimension, i, j, purpose, slot);
        h = mix(h ^ sample);
        return h;
    }

    /** Uniform double in {@code [0, 1)}; 24 random bits are enough for every caller here. */
    static double unit(int hash) {
        return (hash >>> 8) * 0x1.0p-24;
    }

    /** Stable hash of a dimension key; {@link String#hashCode()} is avoided on purpose. */
    static int mixString(String value) {
        int h = 0x811C9DC5;
        for (int k = 0; k < value.length(); k++) {
            h = mix(h ^ value.charAt(k));
        }
        h = mix(h ^ value.length());
        return h;
    }
}
