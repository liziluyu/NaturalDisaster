import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.field.FieldParams;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one point-sample simulation of the mod's pure disaster field for a single chunk.
 *
 * <p>Shared by the command-line analyzer and the local browser analyzer so both report the same
 * numbers for the same inputs.
 */
final class ChunkSimulation {
    static final double TICKS_PER_SECOND = 20.0;
    static final long DEFAULT_TOTAL_TICKS = 12_000_000L;
    static final int DEFAULT_CENTERS_PER_KM2 = 1;
    static final double DEFAULT_KAPPA = 0.35;
    static final int DEFAULT_LAYERS = 2;
    static final double DEFAULT_WALK_SPEED_BLOCKS_PER_TICK = 0.145;
    static final int DEFAULT_DURATION_TICKS = 12_000;
    static final int DEFAULT_RADIUS_CHUNKS = 8;
    static final String DIMENSION = "minecraft:overworld";

    private ChunkSimulation() {
    }

    record Config(long seed, int chunkX, int chunkZ, long totalTicks,
                  int centersPerKm2, double kappa, int layers,
                  double walkSpeedBlocksPerTick, int durationTicks, int radiusChunks) {

        FieldParams fieldParams() {
            return new FieldParams(centersPerKm2, kappa, layers, 8, 0.3,
                    durationTicks, radiusChunks, walkSpeedBlocksPerTick);
        }

        void validate() {
            if (totalTicks <= 0L) {
                throw new IllegalArgumentException("模拟总 tick 必须为正数");
            }
            if (durationTicks <= 2 * FieldParams.WARNING_TICKS) {
                throw new IllegalArgumentException("天灾时长必须大于 "
                        + (2 * FieldParams.WARNING_TICKS / 20) + " 秒（固定预警阶段的两倍）");
            }
            if (centersPerKm2 < 1 || centersPerKm2 > 4096) {
                throw new IllegalArgumentException("centersPerKm2 必须在 1 到 4096 之间");
            }
            if (!Double.isFinite(kappa) || kappa < 0.05 || kappa > 1.15) {
                throw new IllegalArgumentException("kappa 必须在 0.05 到 1.15 之间");
            }
            if (layers < 1 || layers > 2) {
                throw new IllegalArgumentException("layers 必须是 1 或 2");
            }
            if (!Double.isFinite(walkSpeedBlocksPerTick)
                    || walkSpeedBlocksPerTick < 0.01 || walkSpeedBlocksPerTick > 2.0) {
                throw new IllegalArgumentException("walkSpeedBlocksPerTick 必须在 0.01 到 2.0 之间");
            }
            if (radiusChunks < 1 || radiusChunks > 32) {
                throw new IllegalArgumentException("最大半径必须在 1 到 32 区块之间");
            }
            try {
                fieldParams();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("场参数无效: " + e.getMessage(), e);
            }
        }
    }

    /** NaN means "no complete sample", -1 is used for absent duration bounds. */
    record Result(Config config, FieldParams params,
                  long activeTicks, long completeRuns,
                  double averageDurationTicks, long minDurationTicks, long maxDurationTicks,
                  double medianDurationTicks,
                  double averageIdleTicks, long completeIntervals,
                  long elapsedMillis) {

        double occurrenceRatioPercent() {
            return activeTicks * 100.0 / config.totalTicks();
        }

        static double toSeconds(double ticks) {
            return ticks / TICKS_PER_SECOND;
        }
    }

    static Result run(Config config) {
        config.validate();
        long started = System.nanoTime();
        FieldParams params = config.fieldParams();
        DisasterField field = new DisasterField(
                config.seed(), DisasterField.dimensionHash(DIMENSION), params);
        Statistics statistics = new Statistics();
        List<CenterHit> candidates = List.of();
        int chunkX = config.chunkX();
        int chunkZ = config.chunkZ();

        for (long tick = 0L; tick < config.totalTicks(); tick++) {
            if (tick % DisasterField.MOTION_STEP_TICKS == 0L) {
                candidates = maximumRadiusCandidates(field, params, tick, chunkX, chunkZ);
            }

            boolean disasterActive = false;
            for (CenterHit center : candidates) {
                EventState event = field.event(tick, center.gridX(), center.gridZ());
                if (event.active() && DisasterField.coversChunk(
                        center.x(), center.z(), event.radiusChunks(), chunkX, chunkZ)) {
                    disasterActive = true;
                    break;
                }
            }
            statistics.accept(tick, disasterActive);
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return statistics.toResult(config, params, elapsedMillis);
    }

    /** Matches DisasterService's maximum-radius candidate superset for one motion step. */
    private static List<CenterHit> maximumRadiusCandidates(
            DisasterField field, FieldParams params, long tick, int chunkX, int chunkZ) {
        double radius = params.disasterRadiusChunks() * 16.0;
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        List<CenterHit> centers = field.centersInRect(tick,
                minX - radius, minZ - radius, minX + 16.0 + radius, minZ + 16.0 + radius);
        List<CenterHit> result = new ArrayList<>(centers.size());
        for (CenterHit center : centers) {
            if (DisasterField.coversChunk(center.x(), center.z(),
                    params.disasterRadiusChunks(), chunkX, chunkZ)) {
                result.add(center);
            }
        }
        return result;
    }

    private static final class Statistics {
        private long activeTicks;
        private long activeStart = -1L;
        private long lastEnd = -1L;
        private boolean previousActive;

        private long durationTicks;
        private long durationCount;
        private final List<Long> durations = new ArrayList<>();
        private long idleIntervalTicks;
        private long idleIntervalCount;

        private void accept(long tick, boolean active) {
            if (active) {
                activeTicks++;
            }
            if (active && !previousActive) {
                activeStart = tick;
                if (lastEnd >= 0L) {
                    idleIntervalTicks += tick - lastEnd;
                    idleIntervalCount++;
                }
            } else if (!active && previousActive) {
                // Discard a run already active at tick 0; only complete runs define the average.
                if (activeStart > 0L) {
                    long duration = tick - activeStart;
                    durationTicks += duration;
                    durations.add(duration);
                    durationCount++;
                }
                lastEnd = tick;
            }
            previousActive = active;
        }

        private Result toResult(Config config, FieldParams params, long elapsedMillis) {
            double averageDuration = durationCount == 0L
                    ? Double.NaN : (double) durationTicks / durationCount;
            long minimum = durations.stream().min(Long::compare).orElse(-1L);
            long maximum = durations.stream().max(Long::compare).orElse(-1L);
            double median = medianDuration();
            double averageIdle = idleIntervalCount == 0L
                    ? Double.NaN : (double) idleIntervalTicks / idleIntervalCount;
            return new Result(config, params, activeTicks, durationCount,
                    averageDuration, minimum, maximum, median, averageIdle, idleIntervalCount,
                    elapsedMillis);
        }

        private double medianDuration() {
            if (durations.isEmpty()) {
                return Double.NaN;
            }
            List<Long> sorted = new ArrayList<>(durations);
            sorted.sort(null);
            int middle = sorted.size() / 2;
            if (sorted.size() % 2 != 0) {
                return sorted.get(middle);
            }
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
    }
}
