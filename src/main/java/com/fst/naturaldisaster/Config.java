package com.fst.naturaldisaster;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Common config for the disaster-center field.
 *
 * <p>These values are part of the field's configuration fingerprint: changing density/kappa/layers
 * re-shuffles every center, while duration/radius change derived disaster state. They belong to a
 * world snapshot rather than to a per-player preference.
 */
public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final Config INSTANCE;

    public final ForgeConfigSpec.IntValue centersPerKm2;
    public final ForgeConfigSpec.DoubleValue kappa;
    public final ForgeConfigSpec.IntValue layers;
    public final ForgeConfigSpec.IntValue litDuration;
    public final ForgeConfigSpec.IntValue disasterDurationSeconds;
    public final ForgeConfigSpec.IntValue disasterRadiusChunks;

    static {
        Pair<Config, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Config::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private Config(ForgeConfigSpec.Builder builder) {
        builder.comment("Disaster center field. Density/kappa/layers re-shuffle centers; duration/radius change disaster state.").push("centers");

        centersPerKm2 = builder
                .comment("Centers per square kilometre; the only density knob. Grid spacing L = 1000 / sqrt(N).")
                .defineInRange("centersPerKm2", 1, 1, 4096);

        kappa = builder
                .comment("Home-range radius as a multiple of grid spacing. Larger = freer roaming, larger query neighbourhood.")
                .defineInRange("kappa", 0.35, 0.05, 1.15);

        layers = builder
                .comment("Roaming layers. Two layers are the supported product configuration; 1 exists for comparison only.")
                .defineInRange("layers", 2, 1, 2);

        litDuration = builder
                .comment("How long a center stays lit, in ticks (20 ticks = 1 s).")
                .defineInRange("litDuration", 240, 1, 24000);

        disasterDurationSeconds = builder
                .comment("How long one disaster run lasts, in seconds. A run is followed by at least one idle slot.")
                .defineInRange("disasterDurationSeconds", 60, 1, 3600);

        disasterRadiusChunks = builder
                .comment("Disaster influence radius in chunks. A chunk is affected when its centre is inside this radius.")
                .defineInRange("disasterRadiusChunks", 4, 1, 32);

        builder.pop();
    }
}
