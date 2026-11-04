package com.fst.naturaldisaster.disaster;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * An immutable disaster type identified by its registry-style id.
 *
 * <p>Types are declared in code for now; the id is stable so that a later data-driven registry can
 * adopt the same ids without migrating biome mappings.
 */
public record DisasterType(ResourceLocation id) {
    public DisasterType {
        Objects.requireNonNull(id, "id");
    }
}
