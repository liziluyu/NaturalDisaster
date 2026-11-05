package com.fst.naturaldisaster.compat.coldsweat;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.effect.SnowStormEffects;
import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.Function;

/** Relative environmental cooling; preserves Cold Sweat's downstream protection and heat sources. */
public final class SnowStormTempModifier extends TempModifier {
    public SnowStormTempModifier() {
        tickRate(20);
        expires(40);
    }

    @Override
    protected Function<Double, Double> calculate(LivingEntity entity, Temperature.Trait trait) {
        // Check exposure even when the calculated function is cached or restored from saved NBT.
        return temperature -> trait == Temperature.Trait.WORLD && entity instanceof ServerPlayer player
                && SnowStormEffects.isPlayerFreezing(player)
                ? temperature - Temperature.convert(Config.INSTANCE.snowStormCoolingCelsius.get(),
                        Temperature.Units.C, Temperature.Units.MC, false)
                : temperature;
    }
}
