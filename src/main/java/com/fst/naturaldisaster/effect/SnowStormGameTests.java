package com.fst.naturaldisaster.effect;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.network.DisasterRenderPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.util.List;

/** Runtime block rules; discovered only when NeoForge GameTests are enabled. */
@GameTestHolder(NaturalDisaster.MODID)
@PrefixGameTestTemplate(false)
public final class SnowStormGameTests {
    @SuppressWarnings("removal")
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void renderingFlagBelongsToThePlayer(GameTestHelper helper) {
        var player = FreezingGameTests.makePlayer(helper, "rendering");
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var server = helper.getLevel().getServer();
        var dispatcher = new DisasterEffectDispatcher();
        // The mock connection has no login handshake; advertise the client channel explicitly.
        ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection())
                .add(DisasterRenderPayload.TYPE.id());
        try {
            var state = player.getData(PlayerDisasterData.RENDER_STATE);
            helper.assertTrue(!state.isRendering(), "fresh player has no rendering flag");
            dispatcher.tick(server, target -> target == player);
            helper.assertTrue(state.isRendering(), "coverage marks the player for rendering");
            dispatcher.tick(server, target -> target == player);
            helper.assertTrue(player.getData(PlayerDisasterData.RENDER_STATE) == state,
                    "repeated coverage keeps the same player-owned state");
            helper.assertTrue(state.intensity(1f) == 0f, "server does not simulate client visual progress");
            dispatcher.tick(server, target -> false);
            helper.assertTrue(!state.isRendering(), "leaving coverage clears the player flag");
            helper.assertTrue(!player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN),
                    "creative player receives no fallback slowness");
            helper.succeed();
        } finally {
            dispatcher.clear();
            server.getPlayerList().remove(player);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void surfaceRules(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 3, 3));
        // Explicit loading belongs only to this finite test fixture, never to the effect.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz);
            }
        }
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        helper.runAfterDelay(5, () -> {
            try {
                SnowStormBlocks.rebuild(level.registryAccess(), List.of("#c:glass_blocks", "#minecraft:crops"));
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.ICE), "exposed source water freezes");

                level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).isAir(), "default glass tag destroys glass");
                helper.assertTrue(level.getBlockState(pos.above()).isAir(), "destruction does not also place snow");

                level.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
                level.setBlockAndUpdate(pos, Blocks.WHEAT.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).isAir(), "default crops tag destroys wheat");

                SnowStormBlocks.rebuild(level.registryAccess(), List.of("minecraft:bedrock"));
                level.setBlockAndUpdate(pos, Blocks.BEDROCK.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.BEDROCK), "whitelist cannot destroy bedrock");

                SnowStormBlocks.rebuild(level.registryAccess(), List.of("bad entry", "missing:block", "#missing:tag", "minecraft:glass"));
                level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).isAir(), "bad entries preserve valid exact id");

                SnowStormBlocks.rebuild(level.registryAccess(), List.of());
                level.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.GLASS), "empty whitelist disables destruction");
                helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.SNOW), "empty whitelist still permits snow");

                level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                SnowStormEffects.surface(level, pos);
                SnowStormEffects.surface(level, pos.above());
                SnowStormEffects.surface(level, pos.above());
                SnowStormEffects.surface(level, pos.above());
                helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "unlisted stone survives");
                helper.assertTrue(level.getBlockState(pos.above()).getValue(SnowLayerBlock.LAYERS) == 3,
                        "snow stops at three layers");

                level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
                SnowStormEffects.surface(level, pos);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.WATER), "flowing water does not freeze");

                BlockPos glass = pos.offset(2, 3, 0);
                level.setBlockAndUpdate(glass, Blocks.GLASS.defaultBlockState());
                var vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true);
                for (int dy = 0; dy < 3; dy++) {
                    level.setBlockAndUpdate(glass.east().below(dy), vine);
                }
                SnowStormBlocks.rebuild(level.registryAccess(), List.of("minecraft:glass"));
                SnowStormEffects.surface(level, glass);
                for (int dy = 0; dy < 3; dy++) {
                    helper.assertTrue(level.getBlockState(glass.east().below(dy)).isAir(),
                            "normal shape updates remove unsupported hanging vines");
                }
                helper.succeed();
            } finally {
                SnowStormBlocks.rebuild(level.registryAccess(), Config.INSTANCE.snowStormBreakableBlocks.get());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void neighbourUpdatesStayLoaded(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 4, 0));
        int chunkX = (origin.getX() >> 4) + 64;
        int chunkZ = origin.getZ() >> 4;
        BlockPos source = new BlockPos(chunkX * 16 + 15, origin.getY(), chunkZ * 16 + 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
        helper.assertTrue(level.getChunkSource().getChunkNow(chunkX + 2, chunkZ) == null,
                "fixture: outer eastern chunk starts unloaded");
        LoadedEffectScope.run(level, () -> {
            for (int dx = 0; dx <= 15; dx++) {
                level.setBlockAndUpdate(source.offset(dx, -1, 0), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
            for (int dx = 1; dx <= 15; dx++) {
                level.setBlockAndUpdate(source.offset(dx, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
            }
            BlockPos missing = source.offset(17, 0, 0);
            helper.assertTrue(level.getBlockState(missing).isAir(), "missing block read is a boundary");
            helper.assertTrue(level.getFluidState(missing).isEmpty(), "missing fluid read is a boundary");
            helper.assertTrue(level.getBlockEntity(missing) == null, "missing entity read is a boundary");
            helper.assertTrue(!level.setBlockAndUpdate(missing, Blocks.STONE.defaultBlockState()),
                    "writes do not load missing chunks");
        });
        try {
            SnowStormBlocks.rebuild(level.registryAccess(), List.of("minecraft:redstone_block"));
            SnowStormEffects.surface(level, source);
            helper.assertTrue(level.getBlockState(source).isAir(), "configured redstone source is destroyed");
            helper.assertTrue(level.getChunkSource().getChunkNow(chunkX + 2, chunkZ) == null,
                    "redstone neighbour callbacks must not load the outer chunk");
            helper.succeed();
        } finally {
            SnowStormBlocks.rebuild(level.registryAccess(), Config.INSTANCE.snowStormBreakableBlocks.get());
        }
    }
}
