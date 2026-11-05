package com.fst.naturaldisaster.compat.coldsweat;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.effect.SnowStormEffects;
import com.momosoftworks.coldsweat.api.registry.TempModifierRegistry;
import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** GameTest assertions isolated from the absent-mod class path. */
public final class ColdSweatGameTestChecks {
    private ColdSweatGameTestChecks() {
    }

    public static void verify(GameTestHelper helper, ServerPlayer player) {
        double originalCooling = Config.INSTANCE.snowStormCoolingCelsius.get();
        try {
            Config.INSTANCE.snowStormCoolingCelsius.set(20d);
            verifyModifier(helper, player);
        } finally {
            Config.INSTANCE.snowStormCoolingCelsius.set(originalCooling);
        }
    }

    private static void verifyModifier(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(TempModifierRegistry.containsKey(
                ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "snow_storm")), "modifier is registered");
        double core = Temperature.get(player, Temperature.Trait.CORE);
        var originalModifiers = Temperature.getModifiers(player, Temperature.Trait.WORLD);
        for (int tick = 0; tick <= 100; tick++) {
            player.tickCount = tick;
            helper.assertTrue(SnowStormEffects.freezePlayer(player), "Cold Sweat accepts cooling and refreshes");
        }
        var modifiers = Temperature.getModifiers(player, Temperature.Trait.WORLD).stream()
                .filter(mod -> mod instanceof SnowStormTempModifier).toList();
        helper.assertTrue(modifiers.size() == 1, "refreshing never stacks storm modifiers");
        var modifier = modifiers.getFirst();
        double cooled = Temperature.apply(2d, player, Temperature.Trait.WORLD, true, modifier);
        helper.assertTrue(Math.abs(cooled - 1.2d) < 1e-9, "storm applies configured minus 20 Celsius");
        Config.INSTANCE.snowStormCoolingCelsius.set(30d);
        helper.assertTrue(Math.abs(modifier.apply(Temperature.Trait.WORLD, 2d) - 0.8d) < 1e-9,
                "cached modifier function reads changed cooling config");
        Config.INSTANCE.snowStormCoolingCelsius.set(0d);
        helper.assertTrue(modifier.apply(Temperature.Trait.WORLD, 2d) == 2d, "zero cooling disables temperature reduction");
        Config.INSTANCE.snowStormCoolingCelsius.set(20d);
        helper.assertTrue(Temperature.get(player, Temperature.Trait.CORE) == core, "storm does not directly write core temperature");
        helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)
                        && player.getTicksFrozen() == 0,
                "Cold Sweat branch applies neither fallback slowness nor native freezing");
        var encoded = TempModifier.CODEC.encodeStart(NbtOps.INSTANCE, modifier).getOrThrow();
        var restored = TempModifier.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(restored instanceof SnowStormTempModifier, "registered modifier roundtrips through saved NBT");
        SnowStormEffects.stopFreezingPlayer(player);
        helper.assertTrue(!Temperature.hasModifier(player, Temperature.Trait.WORLD, SnowStormTempModifier.class),
                "exit removes only the storm modifier");
        helper.assertTrue(Temperature.getModifiers(player, Temperature.Trait.WORLD).containsAll(originalModifiers),
                "exit preserves all other temperature modifiers");
        double inactive = Temperature.apply(2d, player, Temperature.Trait.WORLD, true, restored);
        helper.assertTrue(inactive == 2d, "saved modifier cannot cool without current player exposure");
    }
}
