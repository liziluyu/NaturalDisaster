import java.util.Locale;

/**
 * Simulates disaster coverage of one chunk using the mod's current pure field implementation.
 * Usage: java DisasterChunkAnalyzer [seed] [chunkX] [chunkZ] [totalTicks]
 *        [centersPerKm2] [kappa] [layers] [durationSeconds] [radiusChunks] [walkSpeedBlocksPerTick]
 */
public final class DisasterChunkAnalyzer {
    private DisasterChunkAnalyzer() {
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        long totalTicks = args.length > 3 ? Long.parseLong(args[3]) : ChunkSimulation.DEFAULT_TOTAL_TICKS;
        int centersPerKm2 = args.length > 4
                ? Integer.parseInt(args[4]) : ChunkSimulation.DEFAULT_CENTERS_PER_KM2;
        double kappa = args.length > 5 ? Double.parseDouble(args[5]) : ChunkSimulation.DEFAULT_KAPPA;
        int layers = args.length > 6 ? Integer.parseInt(args[6]) : ChunkSimulation.DEFAULT_LAYERS;
        double durationSeconds = args.length > 7
                ? Double.parseDouble(args[7]) : ChunkSimulation.DEFAULT_DURATION_TICKS
                        / ChunkSimulation.TICKS_PER_SECOND;
        int radiusChunks = args.length > 8
                ? Integer.parseInt(args[8]) : ChunkSimulation.DEFAULT_RADIUS_CHUNKS;
        double walkSpeed = args.length > 9
                ? Double.parseDouble(args[9]) : ChunkSimulation.DEFAULT_WALK_SPEED_BLOCKS_PER_TICK;

        ChunkSimulation.Config config = new ChunkSimulation.Config(
                seed, chunkX, chunkZ, totalTicks,
                centersPerKm2, kappa, layers, walkSpeed,
                (int) Math.round(durationSeconds * ChunkSimulation.TICKS_PER_SECOND), radiusChunks);
        ChunkSimulation.Result result = ChunkSimulation.run(config);

        System.out.printf(Locale.ROOT,
                "seed=%d, dimension=%s, chunk=(%d,%d), ticks=%d%n",
                seed, ChunkSimulation.DIMENSION, chunkX, chunkZ, totalTicks);
        System.out.printf(Locale.ROOT,
                "centersPerKm2=%d, kappa=%.3f, layers=%d, walkSpeedBlocksPerTick=%.4f, "
                        + "durationTicks=%d, radiusChunks=%d%n",
                centersPerKm2, kappa, layers, walkSpeed, config.durationTicks(), radiusChunks);

        if (result.completeRuns() == 0L) {
            throw new IllegalStateException("No complete disaster runs in the simulation window");
        }
        if (result.completeIntervals() == 0L) {
            throw new IllegalStateException("No complete idle intervals in the simulation window");
        }

        System.out.printf(Locale.ROOT, "occurrenceRatio=%.6f%%%n",
                result.occurrenceRatioPercent());
        System.out.printf(Locale.ROOT, "averageDuration=%.2f ticks (%.2f s), completeRuns=%d%n",
                result.averageDurationTicks(),
                ChunkSimulation.Result.toSeconds(result.averageDurationTicks()),
                result.completeRuns());
        System.out.printf(Locale.ROOT, "durationMin=%d ticks (%.2f s)%n",
                result.minDurationTicks(),
                ChunkSimulation.Result.toSeconds(result.minDurationTicks()));
        System.out.printf(Locale.ROOT, "durationMax=%d ticks (%.2f s)%n",
                result.maxDurationTicks(),
                ChunkSimulation.Result.toSeconds(result.maxDurationTicks()));
        System.out.printf(Locale.ROOT, "durationMedian=%.2f ticks (%.2f s)%n",
                result.medianDurationTicks(),
                ChunkSimulation.Result.toSeconds(result.medianDurationTicks()));
        System.out.printf(Locale.ROOT, "averageIdleInterval=%.2f ticks (%.2f s), completeIntervals=%d%n",
                result.averageIdleTicks(),
                ChunkSimulation.Result.toSeconds(result.averageIdleTicks()),
                result.completeIntervals());
    }
}
