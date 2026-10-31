package com.fst.naturaldisaster.field;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministic invariants of the pure disaster field.
 *
 * <p>These tests never start Minecraft and do not inspect implementation text. Every expected
 * value comes from the design contract (floor semantics, bounded home volume, static coarse-cell
 * ownership, event slot rules) or from brute-force enumeration over public positions.
 */
class DisasterFieldInvariantTest {

    private static final long SEED = 2024L;
    private static final String DIMENSION = "minecraft:overworld";

    private static final FieldParams DEFAULTS =
            new FieldParams(1, 0.35, 2, 8, 0.3, 12000, 8, 0.145);

    private static DisasterField field(FieldParams params) {
        return new DisasterField(SEED, DisasterField.dimensionHash(DIMENSION), params);
    }

    private static FieldParams singleLayer() {
        return new FieldParams(
                DEFAULTS.centersPerKm2(),
                DEFAULTS.kappa(),
                1,
                DEFAULTS.coarseCells(),
                DEFAULTS.coarseKappa(),
                DEFAULTS.disasterDurationTicks(),
                DEFAULTS.disasterRadiusChunks(),
                DEFAULTS.walkSpeedBlocksPerTick());
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static void assertPositionEquals(String message, CenterPosition expected, CenterPosition actual) {
        assertEquals(bits(expected.x()), bits(actual.x()), message + " (x bits)");
        assertEquals(bits(expected.z()), bits(actual.z()), message + " (z bits)");
    }

    private static void assertHitsEqual(String message, List<CenterHit> expected, List<CenterHit> actual) {
        assertEquals(expected.size(), actual.size(), message + " (size)");
        for (int k = 0; k < expected.size(); k++) {
            CenterHit e = expected.get(k);
            CenterHit a = actual.get(k);
            String at = message + " hit[" + k + "]";
            assertEquals(e.gridX(), a.gridX(), at + " gridX");
            assertEquals(e.gridZ(), a.gridZ(), at + " gridZ");
            assertEquals(bits(e.x()), bits(a.x()), at + " x bits");
            assertEquals(bits(e.z()), bits(a.z()), at + " z bits");
            assertEquals(e.radiusChunks(), a.radiusChunks(), at + " radiusChunks");
        }
    }

    private static int chunkOf(double blockCoordinate) {
        return (int) Math.floorDiv((long) Math.floor(blockCoordinate), 16L);
    }

    private static double maxTotalRadius(FieldParams params) {
        double spacing = params.gridSpacing();
        double maxFine = 1.15 * params.kappa() * spacing;
        double maxCoarse = params.layers() >= 2
                ? 1.15 * params.coarseKappa() * spacing * params.coarseCells()
                : 0.0;
        return maxFine + maxCoarse;
    }

    // ---------------------------------------------------------------------------------------
    // 1 s motion quantisation: floor for negative ticks, constant inside a step, pure across steps.
    // ---------------------------------------------------------------------------------------

    @Test
    void motionStepUsesMathematicalFloorForNegativeTicks() {
        DisasterField field = field(DEFAULTS);

        assertEquals(-1L, field.motionStep(-1L));
        assertEquals(-1L, field.motionStep(-20L));
        assertEquals(-2L, field.motionStep(-21L));
        assertEquals(-2L, field.motionStep(-40L));
        assertEquals(-3L, field.motionStep(-41L));
        assertEquals(0L, field.motionStep(0L));
        assertEquals(0L, field.motionStep(19L));
        assertEquals(1L, field.motionStep(20L));
    }

    @Test
    void positionIsConstantInsideAStepAndRecomputedIdenticallyAcrossSteps() {
        DisasterField field = field(DEFAULTS);
        int i = 3;
        int j = -7;

        CenterPosition stepZero = field.centerPosition(0L, i, j);
        assertPositionEquals("tick 19 must reuse the step-0 position",
                stepZero, field.centerPosition(19L, i, j));

        CenterPosition stepMinusOne = field.centerPosition(-1L, i, j);
        assertPositionEquals("tick -20 must reuse the step(-1) position",
                stepMinusOne, field.centerPosition(-20L, i, j));

        CenterPosition stepMinusTwo = field.centerPosition(-21L, i, j);
        assertPositionEquals("tick -40 must reuse the step(-2) position",
                stepMinusTwo, field.centerPosition(-40L, i, j));

        CenterPosition stepOne = field.centerPosition(20L, i, j);

        // A fresh field has no memoised state; it must agree bit-for-bit at each step.
        DisasterField fresh = field(DEFAULTS);
        assertPositionEquals("fresh step 0", stepZero, fresh.centerPosition(13L, i, j));
        assertPositionEquals("fresh step 1", stepOne, fresh.centerPosition(20L, i, j));
        assertPositionEquals("fresh step -1", stepMinusOne, fresh.centerPosition(-7L, i, j));
        assertPositionEquals("fresh step -2", stepMinusTwo, fresh.centerPosition(-39L, i, j));

        // Returning to an earlier step after visiting a later one purges and recomputes the cache.
        assertPositionEquals("return to step 0", stepZero, field.centerPosition(5L, i, j));
        assertPositionEquals("return to step -1", stepMinusOne, field.centerPosition(-1L, i, j));
    }

    // ---------------------------------------------------------------------------------------
    // Memoisation is an optimisation only: repeated / reordered queries must agree.
    // ---------------------------------------------------------------------------------------

    @Test
    void queryResultsDoNotDependOnCacheWarmthOrCallOrder() {
        long[] ticks = {199L, -1L, 200L, -201L, 42600L};
        int[][] chunks = {{0, 0}, {-31, -31}, {-37, 52}, {1875000, -1875000}};

        List<List<CenterHit>> baseline = new ArrayList<>();
        for (long tick : ticks) {
            for (int[] chunk : chunks) {
                baseline.add(field(DEFAULTS).centersAffectingChunk(tick, chunk[0], chunk[1]));
            }
        }

        DisasterField shared = field(DEFAULTS);
        // Warm the cache in reverse order, crossing several motion steps.
        for (int ti = ticks.length - 1; ti >= 0; ti--) {
            for (int ci = chunks.length - 1; ci >= 0; ci--) {
                shared.centersAffectingChunk(ticks[ti], chunks[ci][0], chunks[ci][1]);
            }
        }

        int k = 0;
        for (long tick : ticks) {
            for (int[] chunk : chunks) {
                List<CenterHit> actual = shared.centersAffectingChunk(tick, chunk[0], chunk[1]);
                assertHitsEqual("tick=" + tick + " chunk=" + chunk[0] + "," + chunk[1],
                        baseline.get(k++), actual);
            }
        }

        int[][] cells = {{-1, -1}, {0, 0}, {3, -7}, {8, 1}};
        DisasterField ordered = field(DEFAULTS);
        DisasterField shuffled = field(DEFAULTS);

        CenterPosition[] forward = new CenterPosition[cells.length];
        CenterPosition[] reverse = new CenterPosition[cells.length];
        for (int c = 0; c < cells.length; c++) {
            forward[c] = ordered.centerPosition(199L, cells[c][0], cells[c][1]);
        }
        for (int c = cells.length - 1; c >= 0; c--) {
            reverse[c] = shuffled.centerPosition(199L, cells[c][0], cells[c][1]);
        }
        for (int c = 0; c < cells.length; c++) {
            assertPositionEquals("cell " + c + " order-independent", forward[c], reverse[c]);
        }

        // Visiting a different motion step must not change earlier-step positions.
        shuffled.centerPosition(1000L, 5, 5);
        for (int c = cells.length - 1; c >= 0; c--) {
            assertPositionEquals("cell " + c + " after step change",
                    forward[c], shuffled.centerPosition(199L, cells[c][0], cells[c][1]));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Home domains are bounded: fine plus coarse displacement never grows with time.
    // ---------------------------------------------------------------------------------------

    @Test
    void positionsRemainWithinTheBoundedHomeVolume() {
        double spacing = DEFAULTS.gridSpacing();
        double maxFine = 1.15 * DEFAULTS.kappa() * spacing;
        double maxCoarse = 1.15 * DEFAULTS.coarseKappa() * spacing * DEFAULTS.coarseCells();

        long[] ticks = {0L, 199L, 12345L, -50000L, 135200L, 42600L};
        int[][] cells = {{0, 0}, {1, -1}, {-1, 1}, {-30000, 30000}, {30000, -30000}};

        DisasterField dual = field(DEFAULTS);
        double dualBound = 0.3 * spacing + maxFine + maxCoarse;
        for (long tick : ticks) {
            for (int[] cell : cells) {
                CenterPosition position = dual.centerPosition(tick, cell[0], cell[1]);
                assertTrue(Double.isFinite(position.x()) && Double.isFinite(position.z()),
                        "finite position at tick=" + tick + " cell=" + cell[0] + "," + cell[1]);
                double dx = position.x() - (cell[0] + 0.5) * spacing;
                double dz = position.z() - (cell[1] + 0.5) * spacing;
                assertTrue(dx * dx + dz * dz <= dualBound * dualBound,
                        "dual-layer home bound exceeded at tick=" + tick
                                + " cell=" + cell[0] + "," + cell[1]);
            }
        }

        DisasterField single = field(singleLayer());
        double singleBound = 0.3 * spacing + maxFine;
        for (long tick : ticks) {
            for (int[] cell : cells) {
                CenterPosition position = single.centerPosition(tick, cell[0], cell[1]);
                double dx = position.x() - (cell[0] + 0.5) * spacing;
                double dz = position.z() - (cell[1] + 0.5) * spacing;
                assertTrue(dx * dx + dz * dz <= singleBound * singleBound,
                        "single-layer home bound exceeded at tick=" + tick
                                + " cell=" + cell[0] + "," + cell[1]);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Dual layer: the coarse displacement belongs to the cell of the static fine anchor, not to
    // the coarse cell the fine position currently visits. Observable through public positions.
    // ---------------------------------------------------------------------------------------

    @Test
    void coarseDisplacementIsOwnedByTheStaticFineAnchorX() {
        DisasterField dual = field(DEFAULTS);
        DisasterField single = field(singleLayer());
        double coarseSpacing = DEFAULTS.gridSpacing() * DEFAULTS.coarseCells();

        long tick = 158000L;
        int i = 8;
        int j = 1;

        CenterPosition fine = single.centerPosition(tick, i, j);
        int anchorCell = Math.floorDiv(i, DEFAULTS.coarseCells());
        int positionCell = (int) Math.floor(fine.x() / coarseSpacing);

        assertEquals(1, anchorCell, "test fixture: fine anchor is in coarse cell 1 on X");
        assertEquals(0, positionCell, "test fixture: fine position crossed into coarse cell 0 on X");

        double anchorOffset = dual.centerPosition(tick, i, j).x() - fine.x();
        double sameAnchorCellOffset = dual.centerPosition(tick, i + 1, j).x()
                - single.centerPosition(tick, i + 1, j).x();
        double currentPositionCellOffset = dual.centerPosition(tick, i - 4, j).x()
                - single.centerPosition(tick, i - 4, j).x();

        assertEquals(anchorCell, Math.floorDiv(i + 1, DEFAULTS.coarseCells()),
                "test fixture: sibling belongs to the anchor coarse cell");
        assertEquals(positionCell, Math.floorDiv(i - 4, DEFAULTS.coarseCells()),
                "test fixture: comparison center belongs to the visited coarse cell");

        assertEquals(anchorOffset, sameAnchorCellOffset, 1e-6,
                "same coarse cell must apply the same coarse displacement");
        assertTrue(Math.abs(anchorOffset - currentPositionCellOffset) > 100.0,
                "the two coarse cells must have clearly different displacements for this fixture");
    }

    @Test
    void coarseDisplacementIsOwnedByTheStaticFineAnchorZ() {
        DisasterField dual = field(DEFAULTS);
        DisasterField single = field(singleLayer());
        double coarseSpacing = DEFAULTS.gridSpacing() * DEFAULTS.coarseCells();

        long tick = 135200L;
        int i = -2;
        int j = 16;

        CenterPosition fine = single.centerPosition(tick, i, j);
        int anchorCell = Math.floorDiv(j, DEFAULTS.coarseCells());
        int positionCell = (int) Math.floor(fine.z() / coarseSpacing);

        assertEquals(2, anchorCell, "test fixture: fine anchor is in coarse cell 2 on Z");
        assertEquals(1, positionCell, "test fixture: fine position crossed into coarse cell 1 on Z");

        double anchorOffset = dual.centerPosition(tick, i, j).z() - fine.z();
        double sameAnchorCellOffset = dual.centerPosition(tick, i, j + 1).z()
                - single.centerPosition(tick, i, j + 1).z();
        double currentPositionCellOffset = dual.centerPosition(tick, i, j - 4).z()
                - single.centerPosition(tick, i, j - 4).z();

        assertEquals(anchorCell, Math.floorDiv(j + 1, DEFAULTS.coarseCells()),
                "test fixture: sibling belongs to the anchor coarse cell");
        assertEquals(positionCell, Math.floorDiv(j - 4, DEFAULTS.coarseCells()),
                "test fixture: comparison center belongs to the visited coarse cell");

        assertEquals(anchorOffset, sameAnchorCellOffset, 1e-6,
                "same coarse cell must apply the same coarse displacement");
        assertTrue(Math.abs(anchorOffset - currentPositionCellOffset) > 100.0,
                "the two coarse cells must have clearly different displacements for this fixture");
    }

    // ---------------------------------------------------------------------------------------
    // Coverage: query result must equal brute force over a wider, sufficiently expanded cell box.
    // ---------------------------------------------------------------------------------------

    @Test
    void coverageMatchesExpandedBruteForceIncludingNegativeAndLargeCoordinates() {
        DisasterField field = field(DEFAULTS);

        long[] guaranteedTicks = {0L, 199L, 200L, -1L, -201L, 42600L};
        int[][] guaranteedCells = {
                {0, 0}, {-1, -1}, {3, -7}, {-30000, 30000}, {30000, -30000}
        };

        for (long tick : guaranteedTicks) {
            for (int[] cell : guaranteedCells) {
                CenterPosition position = field.centerPosition(tick, cell[0], cell[1]);
                int chunkX = chunkOf(position.x());
                int chunkZ = chunkOf(position.z());
                List<CenterHit> hits = assertCoverageMatchesBruteForce(field, DEFAULTS, tick, chunkX, chunkZ);
                assertFalse(hits.isEmpty(), "a chunk containing a center position must be covered: tick="
                        + tick + " cell=" + cell[0] + "," + cell[1]);
            }
        }

        long[] ticks = {0L, -1L, 200L, 42600L};
        int[][] chunks = {
                {-1, -1}, {-1875000, 1875000}, {1875000, -1875000}, {1874999, 1875000}
        };
        for (long tick : ticks) {
            for (int[] chunk : chunks) {
                assertCoverageMatchesBruteForce(field, DEFAULTS, tick, chunk[0], chunk[1]);
            }
        }
    }

    private static List<CenterHit> assertCoverageMatchesBruteForce(
            DisasterField field, FieldParams params, long tick, int chunkX, int chunkZ) {
        int maxRadiusChunks = params.disasterRadiusChunks();
        double maxRadiusBlocks = maxRadiusChunks * 16.0;
        double spacing = params.gridSpacing();
        double expand = maxTotalRadius(params) + maxRadiusBlocks;

        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        double maxX = minX + 16.0;
        double maxZ = minZ + 16.0;

        List<CenterHit> actual = field.centersAffectingChunk(tick, chunkX, chunkZ);

        // Brute force deliberately expands two extra cells beyond the theoretical maximum.
        double bruteExpand = expand + 2.0 * spacing;
        int iMin = (int) Math.floor((minX - bruteExpand) / spacing);
        int iMax = (int) Math.floor((maxX + bruteExpand) / spacing);
        int jMin = (int) Math.floor((minZ - bruteExpand) / spacing);
        int jMax = (int) Math.floor((maxZ + bruteExpand) / spacing);

        double centreX = minX + 8.0;
        double centreZ = minZ + 8.0;

        List<CenterHit> brute = new ArrayList<>();
        for (int i = iMin; i <= iMax; i++) {
            for (int j = jMin; j <= jMax; j++) {
                CenterPosition position = field.centerPosition(tick, i, j);
                EventState event = field.event(tick, i, j);
                int radiusChunks = event.active() ? event.radiusChunks() : maxRadiusChunks;
                double radiusBlocks = radiusChunks * 16.0;
                double dx = position.x() - centreX;
                double dz = position.z() - centreZ;
                if (dx * dx + dz * dz <= radiusBlocks * radiusBlocks) {
                    brute.add(new CenterHit(i, j, position.x(), position.z(), radiusChunks));
                }
            }
        }

        assertHitsEqual("tick=" + tick + " chunk=" + chunkX + "," + chunkZ, brute, actual);
        return actual;
    }

    // ---------------------------------------------------------------------------------------
    // Event table: active runs have exactly the configured duration and are separated by at
    // least one complete empty slot.
    // ---------------------------------------------------------------------------------------

    @Test
    void activeEventsLastExactlyDurationAndAreFollowedByAFullEmptySlot() {
        DisasterField field = field(DEFAULTS);
        int duration = DEFAULTS.disasterDurationTicks();
        long from = -20L * duration;
        long to = 20L * duration;

        boolean wasActive = false;
        boolean skipFirstRun = field.event(from, 0, 0).active();
        long runStart = 0L;
        long previousRunEnd = Long.MIN_VALUE;
        int completeRuns = 0;
        int negativeRuns = 0;

        for (long tick = from; tick < to; tick++) {
            EventState state = field.event(tick, 0, 0);
            if (state.active()) {
                assertTrue(state.startTick() <= tick, "active event starts at or before the tick");
                assertTrue(tick < state.startTick() + duration, "active event has not expired");
                assertEquals(tick - state.startTick(), state.elapsed(), "elapsed = tick - startTick");
                assertEquals(duration - state.elapsed(), state.remaining(), "remaining = duration - elapsed");
                if (!wasActive) {
                    runStart = tick;
                }
                wasActive = true;
            } else {
                if (wasActive) {
                    long runEnd = tick;
                    if (skipFirstRun) {
                        // This run began before the scanned window; only complete runs are checked.
                        skipFirstRun = false;
                    } else {
                        assertEquals(duration, runEnd - runStart,
                                "active run at " + runStart + " must last exactly " + duration + " ticks");

                        EventState first = field.event(runStart, 0, 0);
                        assertTrue(first.active(), "run start is active");
                        assertEquals(runStart, first.startTick(), "run start equals startTick");
                        assertEquals(0L, first.elapsed(), "run start has zero elapsed");
                        assertEquals(duration, first.remaining(), "run start has full remaining");

                        EventState last = field.event(runStart + duration - 1, 0, 0);
                        assertTrue(last.active(), "last tick of the run is active");
                        assertEquals(duration - 1L, last.elapsed(), "last tick elapsed");
                        assertEquals(1L, last.remaining(), "last tick remaining");

                        assertFalse(field.event(runStart - 1, 0, 0).active(),
                                "the tick before a run must be inactive");
                        assertFalse(field.event(runStart + duration, 0, 0).active(),
                                "the tick after a run must be inactive");

                        if (previousRunEnd != Long.MIN_VALUE) {
                            assertTrue(runStart - previousRunEnd >= duration,
                                    "runs must be separated by at least one complete empty slot");
                        }
                        previousRunEnd = runEnd;
                        completeRuns++;
                        if (runStart < 0L) {
                            negativeRuns++;
                        }
                    }
                }
                wasActive = false;
            }
        }

        assertTrue(completeRuns > 0, "the scanned window must contain at least one complete run");
        assertTrue(negativeRuns > 0, "negative ticks must participate in the same event table");
    }

    /**
     * The lifecycle is a pure function of the real tick, while the radius is quantised to the
     * 1-second motion frame. Phases, effect gating and the exact rounding directions are checked
     * against an independently written formula.
     */
    @Test
    void lifecyclePhasesAndMotionFrameRadiiFollowTheContract() {
        DisasterField field = field(DEFAULTS);
        int maxRadius = DEFAULTS.disasterRadiusChunks();
        int duration = DEFAULTS.disasterDurationTicks();
        int warning = FieldParams.WARNING_TICKS;
        int growing = duration / 2;
        int decaying = duration - warning - growing;

        boolean sawWarning = false;
        boolean sawGrowing = false;
        boolean sawDecaying = false;
        boolean sawInactive = false;
        boolean sawFrameHeld = false;

        for (long tick = -6L * duration; tick < 6L * duration; tick++) {
            EventState state = field.event(tick, 0, 0);
            if (!state.active()) {
                sawInactive = true;
                assertEquals(EventState.Phase.NONE, state.phase(), "inactive phase at tick " + tick);
                assertFalse(state.effectActive(), "inactive effect at tick " + tick);
                assertEquals(0, state.radiusChunks(), "inactive radius at tick " + tick);
                continue;
            }

            long motionTick = Math.floorDiv(tick, DisasterField.MOTION_STEP_TICKS)
                    * DisasterField.MOTION_STEP_TICKS;
            long e = motionTick - state.startTick();

            EventState.Phase expectedPhase = state.elapsed() < warning
                    ? EventState.Phase.WARNING
                    : state.elapsed() < (long) warning + growing
                            ? EventState.Phase.GROWING
                            : EventState.Phase.DECAYING;
            assertEquals(expectedPhase, state.phase(), "phase at tick " + tick);
            assertEquals(expectedPhase != EventState.Phase.WARNING, state.effectActive(),
                    "effect gating at tick " + tick);

            int expectedRadius = expectedRadius(maxRadius, warning, growing, decaying, e);
            assertEquals(expectedRadius, state.radiusChunks(), "radius at tick " + tick);

            EventState sameFrame = field.event(motionTick, 0, 0);
            if (sameFrame.active() && sameFrame.startTick() == state.startTick()) {
                assertEquals(state.radiusChunks(), sameFrame.radiusChunks(),
                        "radius must be constant inside a motion frame at tick " + tick);
                sawFrameHeld = true;
            }

            switch (expectedPhase) {
                case WARNING -> sawWarning = true;
                case GROWING -> sawGrowing = true;
                case DECAYING -> sawDecaying = true;
                default -> fail("active event must not be NONE");
            }
        }

        assertTrue(sawWarning, "the scanned window must contain a warning stage");
        assertTrue(sawGrowing, "the scanned window must contain a growing stage");
        assertTrue(sawDecaying, "the scanned window must contain a decaying stage");
        assertTrue(sawInactive, "the scanned window must contain an inactive tick");
        assertTrue(sawFrameHeld, "the scanned window must contain a full motion frame");
    }

    private static int expectedRadius(int maxRadius, int warning, int growing, int decaying, long e) {
        double half = maxRadius / 2.0;
        if (e < warning) {
            return (int) -Math.floor(-half);
        }
        if (e < (long) warning + growing) {
            double u = Math.max(0.0, Math.min(1.0, (double) (e - warning) / growing));
            return (int) -Math.floor(-(half + (maxRadius - half) * u));
        }
        double v = Math.max(0.0, Math.min(1.0, (double) (e - warning - growing) / decaying));
        return (int) Math.floor(maxRadius - (maxRadius - half) * v);
    }

    @Test
    void durationMustExceedTwiceTheFixedWarningStage() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldParams(1, 0.35, 2, 8, 0.3, 2 * FieldParams.WARNING_TICKS, 8, 0.145),
                "D == 2W must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FieldParams(1, 0.35, 2, 8, 0.3, FieldParams.WARNING_TICKS, 8, 0.145),
                "D < 2W must be rejected");
        FieldParams minimum = new FieldParams(1, 0.35, 2, 8, 0.3,
                2 * FieldParams.WARNING_TICKS + 1, 8, 0.145);
        assertEquals(1, minimum.decayingTicks(), "D = 2W + 1 leaves a one-tick decaying stage");
    }

    @Test
    void walkSpeedMustBePositiveAndFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldParams(1, 0.35, 2, 8, 0.3, 12000, 8, 0.0),
                "zero walk speed must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new FieldParams(1, 0.35, 2, 8, 0.3, 12000, 8, Double.NaN),
                "NaN walk speed must be rejected");
    }
}
