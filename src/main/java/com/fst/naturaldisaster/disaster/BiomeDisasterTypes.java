package com.fst.naturaldisaster.disaster;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every loaded biome id to a {@link DisasterType}.
 *
 * <p>The mapping is a server-session snapshot built from the datapack-driven biome registry when the
 * server starts. Before {@link #rebuild(RegistryAccess, List)} runs, and for ids absent from the
 * snapshot, the answer is always {@link DisasterTypes#DUMMY}. The table never influences center
 * positions, event timing or {@code FieldParams}; it only selects a type.
 *
 * <p>Configuration entries are exact {@code biomeId=typeId} or tag {@code #tagId=typeId} pairs.
 * Exact biome overrides always win over tags. Tag overrides are tested in configuration order, so
 * the first tag containing a biome wins. A malformed entry, an unknown biome/tag/type is logged
 * and ignored, so a bad config can never prevent the world from starting.
 */
public final class BiomeDisasterTypes {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());

    /** Published atomically so readers never observe a half-built table. */
    private static volatile Snapshot snapshot = EMPTY;

    private BiomeDisasterTypes() {
    }

    /**
     * Checks only the {@code biomeId=typeId} or {@code #tagId=typeId} syntax; it must not touch a
     * registry because the config is validated before any world exists.
     */
    public static boolean isValidOverride(String entry) {
        if (parseOverride(entry) != null) {
            return true;
        }
        LOGGER.warn("Ignoring malformed biome disaster type override '{}'; expected 'biomeId=typeId' or '#tagId=typeId'", entry);
        return false;
    }

    /**
     * Rebuilds the immutable biome-to-type table from the loaded biome registry and bound tags.
     *
     * <p>Every registered biome starts at {@link DisasterTypes#DUMMY}. Exact biome overrides have
     * the highest priority and the last exact entry for a biome wins. Tag overrides are tested in
     * configuration order and the first matching tag wins. Invalid entries and unknown
     * biomes/tags/types are logged and skipped without discarding other entries.
     */
    public static void rebuild(RegistryAccess access, List<? extends String> overrides) {
        if (access == null) {
            snapshot = EMPTY;
            LOGGER.warn("Cannot rebuild biome disaster types without a registry access; every biome falls back to {}",
                    DisasterTypes.DUMMY.id());
            return;
        }

        Registry<Biome> biomes = access.registry(Registries.BIOME).orElse(null);
        if (biomes == null) {
            snapshot = EMPTY;
            LOGGER.warn("The biome registry is unavailable; every biome falls back to {}", DisasterTypes.DUMMY.id());
            return;
        }

        Map<ResourceLocation, DisasterType> exactOverrides = new HashMap<>();
        List<TagOverride> tagOverrides = new ArrayList<>();
        if (overrides != null) {
            for (String entry : overrides) {
                ParsedOverride parsed = parseOverride(entry);
                if (parsed == null) {
                    LOGGER.warn("Ignoring malformed biome disaster type override '{}'; expected 'biomeId=typeId' or '#tagId=typeId'", entry);
                    continue;
                }
                if (!DisasterTypes.isKnown(parsed.typeId())) {
                    LOGGER.warn("Ignoring biome disaster type override '{}': unknown disaster type '{}'",
                            entry, parsed.typeId());
                    continue;
                }

                DisasterType type = DisasterTypes.byId(parsed.typeId());
                if (parsed.tag()) {
                    TagKey<Biome> tag = TagKey.create(Registries.BIOME, parsed.selectorId());
                    if (biomes.getTag(tag).isEmpty()) {
                        LOGGER.warn("Ignoring biome disaster type override '{}': biome tag '{}' is not in the loaded registry",
                                entry, parsed.selectorId());
                        continue;
                    }
                    tagOverrides.add(new TagOverride(tag, type));
                } else {
                    if (!biomes.containsKey(parsed.selectorId())) {
                        LOGGER.warn("Ignoring biome disaster type override '{}': biome '{}' is not in the loaded registry",
                                entry, parsed.selectorId());
                        continue;
                    }
                    exactOverrides.put(parsed.selectorId(), type);
                }
            }
        }

        Map<ResourceLocation, DisasterType> types = new HashMap<>(Math.max(16, biomes.keySet().size() * 2));
        biomes.holders().forEach(holder -> {
            DisasterType type = exactOverrides.get(holder.key().location());
            if (type == null) {
                for (TagOverride tagOverride : tagOverrides) {
                    if (holder.is(tagOverride.tag())) {
                        type = tagOverride.type();
                        break;
                    }
                }
            }
            types.put(holder.key().location(), type != null ? type : DisasterTypes.DUMMY);
        });

        List<ResourceLocation> biomeIds = new ArrayList<>(biomes.keySet());
        biomeIds.sort(ResourceLocation::compareTo);
        snapshot = new Snapshot(Map.copyOf(types), List.copyOf(biomeIds));
    }

    /** Immutable full biome-to-type table for the current server session. */
    public static Map<ResourceLocation, DisasterType> typeByBiome() {
        return snapshot.types();
    }

    /** All registered biome ids, sorted, as scanned during the last rebuild. */
    public static List<ResourceLocation> biomeIds() {
        return snapshot.biomeIds();
    }

    /** Type for a biome id, falling back to {@link DisasterTypes#DUMMY}. */
    public static DisasterType typeFor(ResourceLocation biomeId) {
        DisasterType type = biomeId == null ? null : snapshot.types().get(biomeId);
        return type != null ? type : DisasterTypes.DUMMY;
    }

    /** Type for a biome holder, falling back to {@link DisasterTypes#DUMMY} when it has no key. */
    public static DisasterType typeFor(Holder<Biome> biome) {
        if (biome == null) {
            return DisasterTypes.DUMMY;
        }
        return biome.unwrapKey().map(ResourceKey::location).map(BiomeDisasterTypes::typeFor).orElse(DisasterTypes.DUMMY);
    }

    private static ParsedOverride parseOverride(String entry) {
        if (entry == null) {
            return null;
        }
        int separator = entry.indexOf('=');
        if (separator <= 0 || separator != entry.lastIndexOf('=') || separator == entry.length() - 1) {
            return null;
        }

        String selectorText = entry.substring(0, separator).trim();
        String typeText = entry.substring(separator + 1).trim();
        boolean tag = selectorText.startsWith("#");
        if (tag) {
            selectorText = selectorText.substring(1).trim();
        }
        if (selectorText.isEmpty() || typeText.isEmpty()) {
            return null;
        }

        ResourceLocation selectorId = ResourceLocation.tryParse(selectorText);
        ResourceLocation typeId = ResourceLocation.tryParse(typeText);
        if (selectorId == null || typeId == null) {
            return null;
        }
        return new ParsedOverride(selectorId, typeId, tag);
    }

    private record ParsedOverride(ResourceLocation selectorId, ResourceLocation typeId, boolean tag) {
    }

    private record TagOverride(TagKey<Biome> tag, DisasterType type) {
    }

    private record Snapshot(Map<ResourceLocation, DisasterType> types,
                            List<ResourceLocation> biomeIds) {
    }
}
