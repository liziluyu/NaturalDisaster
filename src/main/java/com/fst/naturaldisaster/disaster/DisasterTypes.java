package com.fst.naturaldisaster.disaster;

import com.fst.naturaldisaster.NaturalDisaster;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * Disaster types declared by the mod.
 *
 * <p>Declaration order is part of the public surface: {@link #all()} returns dummy first, then
 * snow storm. Ids are namespaced like registry entries so config and future data-driven types can
 * refer to them without translation.
 */
public final class DisasterTypes {
    public static final ResourceLocation DUMMY_ID =
            ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "dummy");
    public static final ResourceLocation SNOW_STORM_ID =
            ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "snow_storm");

    public static final DisasterType DUMMY = new DisasterType(DUMMY_ID);
    public static final DisasterType SNOW_STORM = new DisasterType(SNOW_STORM_ID);

    /** Immutable in declaration order; never reorder, consumers may rely on it. */
    private static final List<DisasterType> ALL = List.of(DUMMY, SNOW_STORM);
    private static final Map<ResourceLocation, DisasterType> BY_ID =
            Map.of(DUMMY_ID, DUMMY, SNOW_STORM_ID, SNOW_STORM);

    private DisasterTypes() {
    }

    /** All declared types in declaration order (dummy, snow_storm). */
    public static List<DisasterType> all() {
        return ALL;
    }

    /** Type for an id, or {@link #DUMMY} when the id is unknown or {@code null}. */
    public static DisasterType byId(ResourceLocation id) {
        DisasterType type = id == null ? null : BY_ID.get(id);
        return type != null ? type : DUMMY;
    }

    /** Whether {@code id} is a declared type id. */
    public static boolean isKnown(ResourceLocation id) {
        return id != null && BY_ID.containsKey(id);
    }
}
