package com.fst.naturaldisaster.effect;

import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Server-session destruction whitelist. Tags are matched against their current bindings. */
public final class SnowStormBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static List<Block> blocks = List.of();
    private static List<TagKey<Block>> tags = List.of();

    private SnowStormBlocks() {
    }

    public static void rebuild(RegistryAccess access, List<? extends String> entries) {
        var registry = access.registryOrThrow(Registries.BLOCK);
        List<Block> selectedBlocks = new ArrayList<>();
        List<TagKey<Block>> selectedTags = new ArrayList<>();
        for (String entry : entries) {
            BlockSelector selector = BlockSelector.parse(entry);
            if (selector == null) {
                LOGGER.warn("Ignoring malformed snow storm block selector '{}'", entry);
            } else if (selector.tag()) {
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, selector.id());
                if (registry.getTag(tag).isPresent()) {
                    selectedTags.add(tag);
                } else {
                    LOGGER.warn("Ignoring unknown snow storm block tag '{}'", entry);
                }
            } else if (registry.containsKey(selector.id())) {
                selectedBlocks.add(registry.get(selector.id()));
            } else {
                LOGGER.warn("Ignoring unknown snow storm block '{}'", entry);
            }
        }
        blocks = List.copyOf(selectedBlocks);
        tags = List.copyOf(selectedTags);
    }

    public static boolean matches(BlockState state) {
        for (Block block : blocks) {
            if (state.is(block)) {
                return true;
            }
        }
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }
}
