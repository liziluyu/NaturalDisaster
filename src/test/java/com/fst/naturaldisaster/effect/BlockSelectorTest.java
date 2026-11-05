package com.fst.naturaldisaster.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockSelectorTest {
    @Test
    void distinguishesExactBlocksFromTags() {
        var exact = BlockSelector.parse("minecraft:glass");
        var tag = BlockSelector.parse("#minecraft:crops");
        assertNotNull(exact);
        assertNotNull(tag);
        assertEquals("minecraft:glass", exact.id().toString());
        assertFalse(exact.tag());
        assertEquals("minecraft:crops", tag.id().toString());
        assertTrue(tag.tag());
        assertEquals("c:glass_blocks", BlockSelector.parse(" #c:glass_blocks ").id().toString());
    }

    @Test
    void rejectsMalformedEntriesIndependently() {
        for (String value : new String[]{"", " ", "#", "##minecraft:crops", "minecraft:glass=other:id",
                "minecraft:Glass", "minecraft:*", "#minecraft:bad tag"}) {
            assertNull(BlockSelector.parse(value), value);
        }
        assertNull(BlockSelector.parse(null));
        assertNotNull(BlockSelector.parse("minecraft:glass"));
    }
}
