package com.fst.naturaldisaster.field;

/**
 * Immutable snapshot of every value that participates in the field's configuration fingerprint.
 *
 * <p>Build this once per world (or reload the level) and keep it; changing any value reshuffles
 * every center, so a live config reload must not silently mutate an existing field.
 */
public record FieldParams(
        int centersPerKm2,
        double kappa,
        int layers,
        int coarseCells,
        double coarseKappa,
        int disasterDurationTicks,
        int disasterRadiusChunks,
        double walkSpeedBlocksPerTick) {

    /** Fixed warning-stage length: 60 seconds. Never scaled with the total duration. */
    public static final int WARNING_SECONDS = 60;
    /** Fixed warning-stage length in ticks (20 ticks = 1 s). */
    public static final int WARNING_TICKS = WARNING_SECONDS * 20;

    public FieldParams {
        if (centersPerKm2 < 1) {
            throw new IllegalArgumentException("centersPerKm2 must be >= 1");
        }
        if (kappa <= 0.0 || coarseKappa <= 0.0) {
            throw new IllegalArgumentException("kappa and coarseKappa must be positive");
        }
        if (layers < 1 || layers > 2) {
            throw new IllegalArgumentException("layers must be 1 or 2");
        }
        if (coarseCells < 1) {
            throw new IllegalArgumentException("coarseCells must be >= 1");
        }
        if (disasterDurationTicks <= 2 * WARNING_TICKS) {
            throw new IllegalArgumentException("disasterDurationTicks must be > "
                    + (2 * WARNING_TICKS) + " (total duration must exceed twice the fixed warning stage)");
        }
        if (disasterRadiusChunks < 1) {
            throw new IllegalArgumentException("disasterRadiusChunks must be >= 1");
        }
        if (!Double.isFinite(walkSpeedBlocksPerTick) || walkSpeedBlocksPerTick <= 0.0) {
            throw new IllegalArgumentException("walkSpeedBlocksPerTick must be positive and finite");
        }
    }

    /** Anchor grid spacing L = 1000 / sqrt(N). */
    public double gridSpacing() {
        return 1000.0 / Math.sqrt(centersPerKm2);
    }

    /** Fixed warning-stage length in ticks. */
    public int warningTicks() {
        return WARNING_TICKS;
    }

    /** Growing-stage length in ticks: half of the total duration. */
    public int growingTicks() {
        return disasterDurationTicks / 2;
    }

    /** Decaying-stage length in ticks; strictly positive because {@code D > 2W}. */
    public int decayingTicks() {
        return disasterDurationTicks - WARNING_TICKS - growingTicks();
    }
}
