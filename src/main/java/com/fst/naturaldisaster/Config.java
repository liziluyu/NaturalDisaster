package com.fst.naturaldisaster;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Common config for the disaster-center field and the disaster tick.
 *
 * <p>The center values are part of the field's configuration fingerprint: changing
 * density/kappa/layers re-shuffles every center, walk speed changes path timing, and
 * duration/radius change derived disaster state. They belong to a world snapshot rather than to a
 * per-player preference.
 *
 * <p>The disaster-tick speeds are not part of that fingerprint. They only decide how many
 * coordinates an affected loaded chunk selects per tick and never change center positions, event
 * timing or radii.
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
    public final ModConfigSpec.IntValue surfaceDisasterTickSpeed;
    public final ModConfigSpec.IntValue randomDisasterTickSpeed;
    public final ModConfigSpec.ConfigValue<List<? extends String>> biomeDisasterTypes;
    public final ModConfigSpec.ConfigValue<List<? extends String>> snowStormBreakableBlocks;
    public final ModConfigSpec.DoubleValue snowStormCoolingCelsius;
    public final ModConfigSpec.IntValue snowStormFreezingIntervalTicks;

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

        builder.comment("Disaster tick selection. Speeds only choose how many coordinates each affected loaded chunk selects per tick;",
                        "they are not part of the center field fingerprint and never change center positions or event state.").push("disasterTicks");

        surfaceDisasterTickSpeed = builder
                .comment("Surface disaster-tick samples per affected loaded chunk per tick.",
                        "Each sample picks a random surface column and uses its WORLD_SURFACE top block.",
                        "0 disables surface selection.")
                .defineInRange("surfaceDisasterTickSpeed", 2, 0, 32);

        randomDisasterTickSpeed = builder
                .comment("Random disaster-tick samples per affected loaded chunk per tick.",
                        "Each sample picks a random position across the chunk's full build height.",
                        "0 disables random selection.")
                .defineInRange("randomDisasterTickSpeed", 3, 0, 32);

        builder.pop();

        builder.comment("Biome and biome-tag disaster type overrides. Entries are 'biomeId=typeId' or '#tagId=typeId',",
                        "for example 'minecraft:snowy_plains=naturaldisaster:snow_storm' or",
                        "'#minecraft:is_taiga=naturaldisaster:snow_storm'.",
                        "Exact biome entries always override tags. Tags are tested in list order; the first matching",
                        "tag wins. An empty list uses built-in defaults: snowy/icy biomes get snow_storm, others dummy.",
                        "Built-in snow biomes are tagged naturaldisaster:snow_storm_biomes; explicit config wins.",
                        "Unknown biomes, tags, types or malformed entries are logged and safely ignored; the biome then",
                        "uses the default type. This map only selects a disaster type per biome: it never changes center",
                        "positions, event timing, radii or the center field fingerprint. The map is rebuilt from the",
                        "loaded biome registry and its bound tags when the server starts.")
                .push("disasterTypes");

        biomeDisasterTypes = builder
                .comment("One 'biomeId=typeId' or '#tagId=typeId' override per entry.",
                        "Syntax is validated entry by entry when the server starts, so one malformed string",
                        "cannot reset the whole list to the empty default.")
                .defineListAllowEmpty("biomeToType", List.of(),
                        () -> "minecraft:snowy_plains=naturaldisaster:snow_storm",
                        element -> element instanceof String);

        builder.pop();
        builder.comment("Snow storm effects only; never changes the center field fingerprint or lifecycle.")
                .push("snowStorm");
        snowStormCoolingCelsius = builder
                .comment("Relative ambient cooling in Celsius when Cold Sweat is installed. Positive means colder; 0 disables cooling.",
                        "Effect-only setting; does not change the center field fingerprint or lifecycle.")
                .defineInRange("coolingCelsius", 20.0, 0.0, 200.0);
        snowStormFreezingIntervalTicks = builder
                .comment("Without Cold Sweat: ticks per +1 native frozen tick. Smaller means faster; default 4 is four times slower than powder snow.",
                        "0 disables storm freezing accumulation and allows normal thawing. Slowness remains enabled.",
                        "Effect-only setting; does not change the center field fingerprint or lifecycle.")
                .defineInRange("freezingIntervalTicks", 4, 0, 1200);
        snowStormBreakableBlocks = builder
                .comment("Breakable surface blocks: block ids or #block tags, combined as a union.",
                        "Invalid/unknown entries are warned and skipped at server start.",
                        "Empty disables destruction only; snow and ice remain enabled.")
                .defineListAllowEmpty("breakableBlocks", List.of("#c:glass_blocks", "#minecraft:crops"),
                        () -> "minecraft:glass", value -> value instanceof String);
        builder.pop();
    }
}
