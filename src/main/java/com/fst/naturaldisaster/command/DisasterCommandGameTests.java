package com.fst.naturaldisaster.command;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.disaster.BiomeDisasterTypes;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(NaturalDisaster.MODID)
@PrefixGameTestTemplate(false)
public final class DisasterCommandGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void locateTypeArgument(GameTestHelper helper) throws CommandSyntaxException {
        var level = helper.getLevel();
        var server = level.getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var output = new Output();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // Search away from loaded candidates, so the query exercises uncached event-start types.
        double x = origin.getX() + (origin.getX() < 0 ? 1_000_000 : -1_000_000);
        var source = server.createCommandSourceStack().withLevel(level)
                .withPosition(new Vec3(x, 128, origin.getZ())).withSource(output);
        var parsed = dispatcher.parse("disaster locate naturaldisaster:snow_storm", source);
        helper.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(),
                "locate accepts an optional disaster type");
        var suggestions = dispatcher.getCompletionSuggestions(
                dispatcher.parse("disaster locate naturaldisaster:", source)).join().getList();
        helper.assertTrue(suggestions.stream().anyMatch(s -> s.getText().equals("naturaldisaster:snow_storm")),
                "snow storm id is suggested");
        helper.assertTrue(suggestions.stream().anyMatch(s -> s.getText().equals("naturaldisaster:dummy")),
                "dummy id is suggested");
        int loadedBefore = level.getChunkSource().getLoadedChunksCount();
        try {
            var allSnow = BiomeDisasterTypes.biomeIds().stream()
                    .map(id -> id + "=naturaldisaster:snow_storm").toList();
            BiomeDisasterTypes.rebuild(level.registryAccess(), allSnow);
            helper.assertTrue(dispatcher.execute("disaster locate", source) == 1, "unfiltered locate still works");
            String unfiltered = output.messages.getLast();
            helper.assertTrue(dispatcher.execute("disaster locate naturaldisaster:snow_storm", source) == 1,
                    "requested disaster type can be found");
            helper.assertTrue(output.messages.getLast().equals(unfiltered),
                    "filtering all-snow biomes returns the same nearest center");
            helper.assertTrue(unfiltered.contains("naturaldisaster:snow_storm"), "result reports actual disaster type");
            helper.assertTrue(dispatcher.execute("disaster locate naturaldisaster:dummy", source) == 0,
                    "filter excludes centers of other types");
            helper.assertTrue(output.messages.getLast().contains("naturaldisaster:dummy"),
                    "no-match feedback identifies requested type");
            try {
                dispatcher.execute("disaster locate naturaldisaster:missing", source);
                helper.fail("unknown disaster type must not silently fall back to dummy");
            } catch (CommandSyntaxException expected) {
                helper.assertTrue(expected.getMessage().contains("naturaldisaster:missing"),
                        "unknown-id error identifies the bad type");
            }
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loadedBefore,
                    "locating typed events does not load chunks");
            helper.succeed();
        } finally {
            BiomeDisasterTypes.rebuild(level.registryAccess(), Config.INSTANCE.biomeDisasterTypes.get());
        }
    }

    private static final class Output implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
