package com.fst.naturaldisaster.effect;

import net.minecraft.resources.ResourceLocation;

/** Syntax only; safe to parse before any world or registry exists. */
record BlockSelector(ResourceLocation id, boolean tag) {
    static BlockSelector parse(String entry) {
        if (entry == null) {
            return null;
        }
        String value = entry.trim();
        boolean tag = value.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? value.substring(1) : value);
        return id == null || id.getPath().isEmpty() ? null : new BlockSelector(id, tag);
    }
}
