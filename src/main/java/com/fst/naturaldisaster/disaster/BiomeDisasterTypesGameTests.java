package com.fst.naturaldisaster.disaster;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(NaturalDisaster.MODID)
@PrefixGameTestTemplate(false)
public final class BiomeDisasterTypesGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void snowyDefaultsAndOverrides(GameTestHelper helper) {
        var access = helper.getLevel().registryAccess();
        try {
            BiomeDisasterTypes.rebuild(access, List.of());
            for (var biome : List.of(Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA,
                    Biomes.SNOWY_BEACH, Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.JAGGED_PEAKS,
                    Biomes.FROZEN_PEAKS, Biomes.FROZEN_RIVER, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN)) {
                helper.assertTrue(BiomeDisasterTypes.typeFor(biome.location()).equals(DisasterTypes.SNOW_STORM),
                        biome.location() + " defaults to snow storm even with an existing empty config");
            }
            for (var biome : List.of(Biomes.PLAINS, Biomes.TAIGA, Biomes.STONY_PEAKS, Biomes.DESERT)) {
                helper.assertTrue(BiomeDisasterTypes.typeFor(biome.location()).equals(DisasterTypes.DUMMY),
                        biome.location() + " remains dummy");
            }
            BiomeDisasterTypes.rebuild(access, List.of("minecraft:snowy_plains=naturaldisaster:dummy"));
            helper.assertTrue(BiomeDisasterTypes.typeFor(Biomes.SNOWY_PLAINS.location()).equals(DisasterTypes.DUMMY),
                    "exact config can disable the built-in snow storm default");
            BiomeDisasterTypes.rebuild(access, List.of("#c:is_snowy=naturaldisaster:dummy",
                    "minecraft:snowy_plains=naturaldisaster:snow_storm"));
            helper.assertTrue(BiomeDisasterTypes.typeFor(Biomes.SNOWY_PLAINS.location()).equals(DisasterTypes.SNOW_STORM),
                    "exact overrides still beat tag overrides");
            helper.assertTrue(BiomeDisasterTypes.typeFor(Biomes.SNOWY_TAIGA.location()).equals(DisasterTypes.DUMMY),
                    "tag config beats built-in defaults");
            helper.assertTrue(BiomeDisasterTypes.typeFor(Biomes.FROZEN_RIVER.location()).equals(DisasterTypes.SNOW_STORM),
                    "unmatched snowy defaults survive other overrides");
            helper.succeed();
        } finally {
            BiomeDisasterTypes.rebuild(access, Config.INSTANCE.biomeDisasterTypes.get());
        }
    }
}
