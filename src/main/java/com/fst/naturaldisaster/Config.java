package com.fst.naturaldisaster;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Common config for the disaster-center field.
 *
 * <p>These values are part of the field's configuration fingerprint: changing density/kappa/layers
 * re-shuffles every center, walk speed changes path timing, and duration/radius change derived
 * disaster state. They belong to a world snapshot rather than to a per-player preference.
 */
public final class Config {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;

    public final ModConfigSpec.IntValue centersPerKm2;
    public final ModConfigSpec.DoubleValue kappa;
    public final ModConfigSpec.DoubleValue walkSpeedBlocksPerTick;
    public final ModConfigSpec.IntValue layers;
    public final ModConfigSpec.IntValue disasterDurationSeconds;
    public final ModConfigSpec.IntValue disasterRadiusChunks;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private Config(ModConfigSpec.Builder builder) {
        builder.comment("Disaster center field. Density/kappa/layers re-shuffle centers; walk speed changes path timing; duration/radius change disaster state.").push("centers");

        centersPerKm2 = builder
                .comment("Centers per square kilometre; the only density knob. Grid spacing L = 1000 / sqrt(N).")
                .defineInRange("centersPerKm2", 1, 1, 4096);

        kappa = builder
                .comment("Home-range radius as a multiple of grid spacing. Larger = freer roaming, larger query neighbourhood.")
                .defineInRange("kappa", 0.35, 0.05, 1.15);

        walkSpeedBlocksPerTick = builder
                .comment("Average center roaming speed in blocks per tick.",
                        "Default 0.145; affects path timing, not the sampled trajectory shape.")
                .defineInRange("walkSpeedBlocksPerTick", 0.145, 0.01, 2.0);

        layers = builder
                .comment("Roaming layers. Two layers are the supported product configuration; 1 exists for comparison only.")
                .defineInRange("layers", 2, 1, 2);

        disasterDurationSeconds = builder
                .comment("How long one disaster run lasts, in seconds, including the fixed 60 s warning stage.",
                        "Default 600 s = 12000 ticks at 20 ticks/s.",
                        "Must be greater than 120 s so the decaying stage stays positive.",
                        "A run is followed by at least one idle slot.")
                .defineInRange("disasterDurationSeconds", 600, 2 * 60 + 1, 3600);

        disasterRadiusChunks = builder
                .comment("Maximum disaster influence radius in chunks. A chunk is affected when its centre is",
                        "inside the event's current radius; candidate queries always expand by this maximum.")
                .defineInRange("disasterRadiusChunks", 8, 1, 32);

        builder.pop();
    }
}
