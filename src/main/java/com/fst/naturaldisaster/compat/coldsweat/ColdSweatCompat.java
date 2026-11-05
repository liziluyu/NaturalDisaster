package com.fst.naturaldisaster.compat.coldsweat;

import com.fst.naturaldisaster.NaturalDisaster;
import com.momosoftworks.coldsweat.api.event.core.registry.TempModifierRegisterEvent;
import com.momosoftworks.coldsweat.api.temperature.modifier.BlockTempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.api.util.placement.Matcher;
import com.momosoftworks.coldsweat.api.util.placement.Mode;
import com.momosoftworks.coldsweat.api.util.placement.Order;
import com.momosoftworks.coldsweat.api.util.placement.Placement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

/** Only loaded behind COLD_SWEAT_LOADED. Cold Sweat owns body temperature and synchronization. */
public final class ColdSweatCompat {
    private ColdSweatCompat() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColdSweatCompat::registerModifiers);
    }

    private static void registerModifiers(TempModifierRegisterEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "snow_storm"),
                SnowStormTempModifier::new);
    }

    public static boolean freezePlayer(ServerPlayer player) {
        var existing = Temperature.getModifier(player, Temperature.Trait.WORLD, SnowStormTempModifier.class);
        if (existing.isPresent() && player.tickCount % 20 != 0) {
            return true;
        }
        // Replace in place; new instances precede block heat and thermal-source protection.
        // Build fresh placements: Cold Sweat's static FIRST/LAST objects are mutable.
        Placement placement = Placement.of(Mode.REPLACE, Order.FIRST, mod -> mod instanceof SnowStormTempModifier)
                .orElse(Placement.of(Mode.ADD_BEFORE, Order.FIRST, mod -> mod instanceof BlockTempModifier)
                        .noDuplicates(Matcher.SAME_CLASS)
                        .orElse(Placement.of(Mode.ADD_AFTER, Order.LAST, mod -> true).noDuplicates(Matcher.SAME_CLASS)));
        return Temperature.addModifier(player, new SnowStormTempModifier(), Temperature.Trait.WORLD,
                placement);
    }

    public static void stopFreezingPlayer(ServerPlayer player) {
        if (Temperature.hasModifier(player, Temperature.Trait.WORLD, SnowStormTempModifier.class)) {
            Temperature.removeModifiers(player, Temperature.Trait.WORLD, SnowStormTempModifier.class);
        }
    }
}
