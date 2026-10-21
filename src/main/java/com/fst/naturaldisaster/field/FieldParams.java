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
        int disasterRadiusChunks) {

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
        if (disasterDurationTicks < 1) {
            throw new IllegalArgumentException("disasterDurationTicks must be >= 1");
        }
        if (disasterRadiusChunks < 1) {
            throw new IllegalArgumentException("disasterRadiusChunks must be >= 1");
        }
    }

    /** Anchor grid spacing L = 1000 / sqrt(N). */
    public double gridSpacing() {
        return 1000.0 / Math.sqrt(centersPerKm2);
    }
}
