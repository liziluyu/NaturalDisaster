package com.fst.naturaldisaster.field;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure-function disaster-center field.
 *
 * <p>Everything here is a deterministic function of {@code (seed, dimension, tick, grid cell)}.
 * There is no mutable center state and nothing is persisted. The only temporal memoisation is a
 * per-step position cache described below.
 *
 * <p><b>1-second motion quantisation.</b> Center movement is evaluated at steps of
 * {@link #MOTION_STEP_TICKS} ticks (1 s). Within a step the position is constant; the next step
 * recomputes it. The cache is cleared whenever the step changes, so the spline is evaluated at most
 * once per center per 1 s instead of once per tick.
 *
 * <p>No trigonometric functions are used at runtime; directions come from {@link DirectionTable}.
 */
public final class DisasterField {
    /** 1 second at 20 ticks/s. */
    public static final long MOTION_STEP_TICKS = 20L;

    /** Segment length T = SEGMENT_FACTOR * R / configured walk speed. */
    private static final double SEGMENT_FACTOR = 1.3;
    /** Waypoint radius as a fraction of the home radius. */
    private static final double WAYPOINT_RADIUS = 0.72;
    /** Per-center home-radius multiplier range. */
    private static final double SIGMA_MIN = 0.85;
    private static final double SIGMA_RANGE = 0.30;
    /** Waypoint low-discrepancy increments. */
    private static final double ALPHA = 1697.0 / 4096.0;
    private static final double GAMMA = 2531.0 / 4096.0;
    /** Per-slot trigger chance of the pure event table. */
    private static final double EVENT_CHANCE = 0.35;

    // Purpose labels. Each independent random decision gets its own label.
    private static final int P_ANCHOR_X = 1;
    private static final int P_ANCHOR_Z = 2;
    private static final int P_SIGMA = 3;
    private static final int P_TAU = 4;
    private static final int P_BETA = 5;
    private static final int P_ETA = 6;
    private static final int P_COARSE_ANCHOR_X = 11;
    private static final int P_COARSE_ANCHOR_Z = 12;
    private static final int P_COARSE_SIGMA = 13;
    private static final int P_COARSE_TAU = 14;
    private static final int P_COARSE_BETA = 15;
    private static final int P_COARSE_ETA = 16;
    private static final int P_EVENT_PHASE = 21;
    private static final int P_EVENT_SLOT = 22;

    private final long seed;
    private final int dimensionHash;
    private final FieldParams params;

    private final double spacing;
    private final double coarseSpacing;
    private final boolean dualLayer;
    private final double maxFineRadius;
    private final double maxCoarseRadius;
    private final double maxTotalRadius;

    /** Positions for the current 1-second step only; cleared on step change. */
    private final Long2ObjectOpenHashMap<CenterPosition> fineCache = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<CenterPosition> coarseCache = new Long2ObjectOpenHashMap<>();
    private long cachedStep = Long.MIN_VALUE;

    public DisasterField(long seed, int dimensionHash, FieldParams params) {
        this.seed = seed;
        this.dimensionHash = dimensionHash;
        this.params = params;
        this.spacing = params.gridSpacing();
        this.coarseSpacing = spacing * params.coarseCells();
        this.dualLayer = params.layers() >= 2;
        this.maxFineRadius = 1.15 * params.kappa() * spacing;
        this.maxCoarseRadius = dualLayer ? 1.15 * params.coarseKappa() * coarseSpacing : 0.0;
        this.maxTotalRadius = maxFineRadius + maxCoarseRadius;
    }

    public FieldParams params() {
        return params;
    }

    /** Stable numeric hash of a dimension key for the field's domain separation. */
    public static int dimensionHash(String dimensionKey) {
        return Hash.mixString(dimensionKey);
    }

    /** Step index for a tick; negative ticks floor correctly. */
    public long motionStep(long tick) {
        return Math.floorDiv(tick, MOTION_STEP_TICKS);
    }

    /**
     * Center position at the 1-second step containing {@code tick}, in block coordinates.
     *
     * @param i fine anchor grid X
     * @param j fine anchor grid Z
     */
    public CenterPosition centerPosition(long tick, int i, int j) {
        long step = Math.floorDiv(tick, MOTION_STEP_TICKS);
        if (step != cachedStep) {
            cachedStep = step;
            fineCache.clear();
            coarseCache.clear();
        }

        long key = key(i, j);
        CenterPosition cached = fineCache.get(key);
        if (cached != null) {
            return cached;
        }

        long motionTick = step * MOTION_STEP_TICKS;
        CenterPosition position = computePosition(motionTick, i, j);
        fineCache.put(key, position);
        return position;
    }

    private CenterPosition computePosition(long motionTick, int i, int j) {
        double ax = (i + 0.5 + jitter(i, j, P_ANCHOR_X)) * spacing;
        double az = (j + 0.5 + jitter(i, j, P_ANCHOR_Z)) * spacing;
        double sigma = SIGMA_MIN + SIGMA_RANGE * Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_SIGMA));
        double homeRadius = params.kappa() * spacing * sigma;
        double segment = SEGMENT_FACTOR * homeRadius / params.walkSpeedBlocksPerTick();
        double tau = Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_TAU)) * segment;
        double beta = Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_BETA));
        double eta = Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_ETA));

        double[] fine = roam(motionTick, ax, az, homeRadius, tau, beta, eta);
        if (!dualLayer) {
            return new CenterPosition(fine[0], fine[1]);
        }

        // Static coarse-cell ownership: the fine anchor decides which coarse displacement applies.
        int bi = (int) Math.floor(ax / coarseSpacing);
        int bj = (int) Math.floor(az / coarseSpacing);
        long coarseKey = key(bi, bj);
        CenterPosition coarsePosition = coarseCache.get(coarseKey);
        if (coarsePosition == null) {
            coarsePosition = computeCoarsePosition(motionTick, bi, bj);
            coarseCache.put(coarseKey, coarsePosition);
        }

        double cax = (bi + 0.5 + jitterCoarse(bi, bj, P_COARSE_ANCHOR_X)) * coarseSpacing;
        double caz = (bj + 0.5 + jitterCoarse(bi, bj, P_COARSE_ANCHOR_Z)) * coarseSpacing;
        return new CenterPosition(
                fine[0] + coarsePosition.x() - cax,
                fine[1] + coarsePosition.z() - caz);
    }

    private CenterPosition computeCoarsePosition(long motionTick, int bi, int bj) {
        double ax = (bi + 0.5 + jitterCoarse(bi, bj, P_COARSE_ANCHOR_X)) * coarseSpacing;
        double az = (bj + 0.5 + jitterCoarse(bi, bj, P_COARSE_ANCHOR_Z)) * coarseSpacing;
        double sigma = SIGMA_MIN + SIGMA_RANGE
                * Hash.unit(Hash.mix(seed, dimensionHash, bi, bj, P_COARSE_SIGMA));
        double homeRadius = params.coarseKappa() * coarseSpacing * sigma;
        double segment = SEGMENT_FACTOR * homeRadius / params.walkSpeedBlocksPerTick();
        double tau = Hash.unit(Hash.mix(seed, dimensionHash, bi, bj, P_COARSE_TAU)) * segment;
        double beta = Hash.unit(Hash.mix(seed, dimensionHash, bi, bj, P_COARSE_BETA));
        double eta = Hash.unit(Hash.mix(seed, dimensionHash, bi, bj, P_COARSE_ETA));
        double[] position = roam(motionTick, ax, az, homeRadius, tau, beta, eta);
        return new CenterPosition(position[0], position[1]);
    }

    /**
     * All centers whose circular influence covers the given chunk.
     *
     * <p>The influence radius is expressed in chunks. A chunk is covered when its centre is within
     * {@code radiusChunks * 16} blocks of the center. The candidate range is expanded by the maximum
     * possible home-range displacement, so no center can be missed. Cost is bounded by the
     * configured density/kappa/radius, never by world size or elapsed time.
     */
    public List<CenterHit> centersAffectingChunk(long tick, int chunkX, int chunkZ) {
        int maxRadiusChunks = params.disasterRadiusChunks();
        double maxRadiusBlocks = maxRadiusChunks * 16.0;
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        double maxX = minX + 16.0;
        double maxZ = minZ + 16.0;
        // Candidates are always expanded by the configured maximum radius, never by the radius an
        // individual event happens to have on this frame.
        double expand = maxTotalRadius + maxRadiusBlocks;

        int iMin = (int) Math.floor((minX - expand) / spacing);
        int iMax = (int) Math.floor((maxX + expand) / spacing);
        int jMin = (int) Math.floor((minZ - expand) / spacing);
        int jMax = (int) Math.floor((maxZ + expand) / spacing);

        List<CenterHit> hits = null;
        for (int i = iMin; i <= iMax; i++) {
            for (int j = jMin; j <= jMax; j++) {
                CenterPosition position = centerPosition(tick, i, j);
                int radiusChunks = hitRadius(tick, i, j, maxRadiusChunks);
                if (coversChunk(position.x(), position.z(), radiusChunks, chunkX, chunkZ)) {
                    if (hits == null) {
                        hits = new ArrayList<>(2);
                    }
                    hits.add(new CenterHit(i, j, position.x(), position.z(), radiusChunks));
                }
            }
        }
        return hits == null ? List.of() : hits;
    }

    /**
     * Radius used for coverage: the event's current radius while it is active, the configured
     * maximum otherwise. Inactive centers keep the maximum-radius semantics that existing commands
     * rely on.
     */
    private int hitRadius(long tick, int i, int j, int maxRadiusChunks) {
        EventState event = event(tick, i, j);
        return event.active() ? event.radiusChunks() : maxRadiusChunks;
    }

    /**
     * Pure coverage test shared by every query: a chunk is affected when its centre is within
     * {@code radiusChunks * 16} blocks of the center.
     */
    public static boolean coversChunk(double centerX, double centerZ, int radiusChunks, int chunkX, int chunkZ) {
        double dx = chunkX * 16.0 + 8.0 - centerX;
        double dz = chunkZ * 16.0 + 8.0 - centerZ;
        double radiusBlocks = radiusChunks * 16.0;
        return dx * dx + dz * dz <= radiusBlocks * radiusBlocks;
    }

    /**
     * All centers whose current position lies inside the given rectangle.
     *
     * <p>Like every other query here this is a pure function of the tick and the rectangle.
     * Candidate grid cells are expanded by the maximum possible home-range displacement, so a
     * center can never be missed; each candidate is then filtered by its actual position. Cost is
     * proportional to the rectangle's area times the configured density, never to world size or
     * elapsed time. Centers are returned in ascending {@code i}, then {@code j} order.
     *
     * @param minX inclusive minimum block X
     * @param minZ inclusive minimum block Z
     * @param maxX inclusive maximum block X
     * @param maxZ inclusive maximum block Z
     */
    public List<CenterHit> centersInRect(long tick, double minX, double minZ, double maxX, double maxZ) {
        if (maxX < minX || maxZ < minZ) {
            return List.of();
        }

        double expand = maxTotalRadius;
        int iMin = (int) Math.floor((minX - expand) / spacing);
        int iMax = (int) Math.floor((maxX + expand) / spacing);
        int jMin = (int) Math.floor((minZ - expand) / spacing);
        int jMax = (int) Math.floor((maxZ + expand) / spacing);

        int maxRadiusChunks = params.disasterRadiusChunks();
        List<CenterHit> hits = null;
        for (int i = iMin; i <= iMax; i++) {
            for (int j = jMin; j <= jMax; j++) {
                CenterPosition position = centerPosition(tick, i, j);
                if (position.x() < minX || position.x() > maxX
                        || position.z() < minZ || position.z() > maxZ) {
                    continue;
                }
                int radiusChunks = hitRadius(tick, i, j, maxRadiusChunks);
                if (hits == null) {
                    hits = new ArrayList<>(2);
                }
                hits.add(new CenterHit(i, j, position.x(), position.z(), radiusChunks));
            }
        }
        return hits == null ? List.of() : hits;
    }

    /**
     * Pure event table: whether the center's disaster is running at {@code tick}.
     *
     * <p>Slots have the configured duration. A slot triggers with a fixed chance, and the previous
     * slot must not have triggered, so an active run is exactly {@code disasterDurationTicks} long
     * and is followed by at least one empty slot. This slot construction is independent of visuals.
     */
    public EventState event(long tick, int i, int j) {
        int duration = params.disasterDurationTicks();
        int phase = (int) (Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_EVENT_PHASE)) * duration);
        long shifted = tick + phase;
        long slot = Math.floorDiv(shifted, duration);

        boolean current = Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_EVENT_SLOT, slot)) < EVENT_CHANCE;
        if (!current) {
            return EventState.INACTIVE;
        }
        boolean previous = Hash.unit(Hash.mix(seed, dimensionHash, i, j, P_EVENT_SLOT, slot - 1)) < EVENT_CHANCE;
        if (previous) {
            return EventState.INACTIVE;
        }

        long startTick = slot * duration - phase;
        long elapsed = tick - startTick;

        // Existence, phase and effect are decided by the real tick.
        int warning = params.warningTicks();
        int growing = params.growingTicks();
        EventState.Phase lifePhase;
        if (elapsed < warning) {
            lifePhase = EventState.Phase.WARNING;
        } else if (elapsed < (long) warning + growing) {
            lifePhase = EventState.Phase.GROWING;
        } else {
            lifePhase = EventState.Phase.DECAYING;
        }
        boolean effectActive = lifePhase != EventState.Phase.WARNING;

        // Only the radius is quantised to the 1-second motion frame.
        long motionTick = Math.floorDiv(tick, MOTION_STEP_TICKS) * MOTION_STEP_TICKS;
        int radiusChunks = radiusAt(motionTick, startTick);

        return new EventState(true, startTick, elapsed, duration - elapsed,
                lifePhase, effectActive, radiusChunks);
    }

    /**
     * Integer radius for the motion frame that starts at {@code motionTick}.
     *
     * <p>{@code e = motionTick - startTick}. Warning uses {@code ceil(R/2)}; growing uses
     * {@code ceil(B + (R-B) * clamp((e-W)/G))}; decaying uses
     * {@code floor(R - (R-B) * clamp((e-W-G)/F))}. The asymmetric rounding is intentional.
     */
    private int radiusAt(long motionTick, long startTick) {
        int maxRadius = params.disasterRadiusChunks();
        double half = maxRadius / 2.0;
        long e = motionTick - startTick;
        int warning = params.warningTicks();
        int growing = params.growingTicks();
        int decaying = params.decayingTicks();

        if (e < warning) {
            return ceilToInt(half);
        }
        if (e < (long) warning + growing) {
            double u = clamp((double) (e - warning) / growing, 0.0, 1.0);
            return ceilToInt(half + (maxRadius - half) * u);
        }
        double v = decaying <= 0 ? 1.0 : clamp((double) (e - warning - growing) / decaying, 0.0, 1.0);
        return (int) Math.floor(maxRadius - (maxRadius - half) * v);
    }

    private static int ceilToInt(double value) {
        return (int) -Math.floor(-value);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Writes the waypoint at segment index {@code e} into {@code out[offset..offset+1]}. */
    private void writeWaypoint(long e, double ax, double az, double homeRadius,
                               double beta, double eta, double[] out, int offset) {
        double u = frac(e * ALPHA + beta);
        double v = frac(e * GAMMA + eta);
        int index = DirectionTable.index(v);
        double r = WAYPOINT_RADIUS * homeRadius * Math.sqrt(u);
        out[offset] = ax + r * DirectionTable.COS[index];
        out[offset + 1] = az + r * DirectionTable.SIN[index];
    }

    /** Catmull-Rom position inside the home range. */
    private double[] roam(long motionTick, double ax, double az, double homeRadius,
                          double tau, double beta, double eta) {
        double segment = SEGMENT_FACTOR * homeRadius / params.walkSpeedBlocksPerTick();
        double raw = (motionTick + tau) / segment;
        long e = (long) Math.floor(raw);
        double u = raw - e;

        double[] w = new double[8];
        writeWaypoint(e - 1, ax, az, homeRadius, beta, eta, w, 0);
        writeWaypoint(e, ax, az, homeRadius, beta, eta, w, 2);
        writeWaypoint(e + 1, ax, az, homeRadius, beta, eta, w, 4);
        writeWaypoint(e + 2, ax, az, homeRadius, beta, eta, w, 6);

        return new double[]{
                catmullRom(w[0], w[2], w[4], w[6], u),
                catmullRom(w[1], w[3], w[5], w[7], u)
        };
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (2.0 * p1
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    private double jitter(int i, int j, int purpose) {
        return Hash.unit(Hash.mix(seed, dimensionHash, i, j, purpose)) * 0.6 - 0.3;
    }

    private double jitterCoarse(int i, int j, int purpose) {
        return Hash.unit(Hash.mix(seed, dimensionHash, i, j, purpose)) * 0.6 - 0.3;
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }
}
