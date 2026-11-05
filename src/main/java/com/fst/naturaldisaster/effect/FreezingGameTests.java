package com.fst.naturaldisaster.effect;

import com.fst.naturaldisaster.Config;
import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.compat.coldsweat.ColdSweatGameTestChecks;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runs the native branch without Cold Sweat, and the real modifier branch with -PwithColdSweat=true. */
@GameTestHolder(NaturalDisaster.MODID)
@PrefixGameTestTemplate(false)
public final class FreezingGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void installedAndFallbackFreezing(GameTestHelper helper) {
        var player = makePlayer(helper, "freezing");
        int originalInterval = Config.INSTANCE.snowStormFreezingIntervalTicks.get();
        try {
            if (NaturalDisaster.COLD_SWEAT_LOADED) {
                ColdSweatGameTestChecks.verify(helper, player);
            } else {
                Config.INSTANCE.snowStormFreezingIntervalTicks.set(4);
                verifyNativeFreezing(helper, player);
            }
            helper.succeed();
        } finally {
            Config.INSTANCE.snowStormFreezingIntervalTicks.set(originalInterval);
            SnowStormEffects.stopFreezingPlayer(player);
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    private static void verifyNativeFreezing(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(SnowStormEffects.freezePlayer(player), "eligible player starts freezing");
        var slow = player.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        helper.assertTrue(slow != null && slow.getAmplifier() == 0 && slow.getDuration() == 60,
                "fallback applies Slowness I for 60 ticks");
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2));
        for (int tick = 1; tick <= 80; tick++) {
            // Advance normal server-player bookkeeping, including the 60-tick spawn immunity.
            player.tick();
            player.tickCount = tick;
            SnowStormEffects.freezePlayer(player);
            player.aiStep();
        }
        helper.assertTrue(player.getTicksFrozen() == 20, "80 native aiSteps accumulate exactly 20 frozen ticks");
        helper.assertTrue(player.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() == 2,
                "fallback preserves stronger existing slowness");

        SnowStormEffects.stopFreezingPlayer(player);
        player.aiStep();
        helper.assertTrue(player.getTicksFrozen() == 18, "exit restores vanilla thawing by two ticks");
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        SnowStormEffects.freezePlayer(player);
        player.aiStep();
        helper.assertTrue(player.getTicksFrozen() == 16, "leather immunity keeps vanilla thawing");
        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.setTicksFrozen(0);
        for (int tick = 1; tick <= 8; tick++) {
            player.tickCount = tick;
            player.setIsInPowderSnow(true);
            player.aiStep();
        }
        helper.assertTrue(player.getTicksFrozen() == 8, "real powder snow still freezes every tick");
        player.setIsInPowderSnow(false);

        Config.INSTANCE.snowStormFreezingIntervalTicks.set(2);
        player.setTicksFrozen(0);
        for (int tick = 1; tick <= 8; tick++) {
            player.tickCount = tick;
            SnowStormEffects.freezePlayer(player);
            player.aiStep();
        }
        helper.assertTrue(player.getTicksFrozen() == 4, "changing the configured interval changes native buildup speed");
        Config.INSTANCE.snowStormFreezingIntervalTicks.set(0);
        player.setTicksFrozen(10);
        player.aiStep();
        helper.assertTrue(player.getTicksFrozen() == 8, "zero interval disables storm buildup and restores thawing");
        Config.INSTANCE.snowStormFreezingIntervalTicks.set(4);
        player.setTicksFrozen(139);
        player.tickCount = 40;
        float health = player.getHealth();
        SnowStormEffects.freezePlayer(player);
        player.aiStep();
        helper.assertTrue(player.getTicksFrozen() == 140, "fallback caps at the vanilla full-freeze threshold");
        helper.assertTrue(player.getHealth() < health, "fully frozen player receives native freeze damage");
        player.setTicksFrozen(140);
        player.tickCount = 44;
        SnowStormEffects.freezePlayer(player);
        player.aiStep();
        helper.assertTrue(player.getTicksFrozen() == 140, "continued exposure cannot exceed full freezing");

        EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        var armorItems = new net.minecraft.world.item.Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS};
        for (int i = 0; i < armorSlots.length; i++) {
            player.setItemSlot(armorSlots[i], new ItemStack(armorItems[i]));
        }
        player.invulnerableTime = 0;
        player.setHealth(player.getMaxHealth());
        health = player.getHealth();
        player.tickCount = 80;
        SnowStormEffects.freezePlayer(player);
        player.aiStep();
        helper.assertTrue(player.getHealth() == health, "all four armor slots protect against storm freeze damage");
        helper.assertTrue(player.getTicksFrozen() == 140, "full armor does not reset frozen ticks");
        for (int i = 0; i < armorSlots.length; i++) {
            player.setItemSlot(armorSlots[i], ItemStack.EMPTY);
            player.invulnerableTime = 0;
            player.setHealth(player.getMaxHealth());
            health = player.getHealth();
            player.tickCount = 120 + i * 40;
            SnowStormEffects.freezePlayer(player);
            player.aiStep();
            helper.assertTrue(player.getHealth() < health, "missing armor slot " + armorSlots[i] + " permits native freeze damage");
            player.setItemSlot(armorSlots[i], new ItemStack(armorItems[i]));
        }
        player.invulnerableTime = 0;
        player.setHealth(player.getMaxHealth());
        health = player.getHealth();
        player.tickCount = 320;
        player.setIsInPowderSnow(true);
        player.aiStep();
        helper.assertTrue(player.getHealth() < health, "storm armor protection does not change real powder snow damage");
        player.setIsInPowderSnow(false);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void exposureStopsAndIgnoresCreativePlayers(GameTestHelper helper) {
        var player = makePlayer(helper, "exposure");
        var staleExposure = new SnowStormExposure();
        staleExposure.refresh(player.level());
        try {
            SnowStormEffects.freezePlayer(player);
            helper.assertTrue(SnowStormEffects.isPlayerFreezing(player), "exposure belongs to this player");
            SnowStormEffects.stopFreezingPlayer(player);
            helper.assertTrue(!SnowStormEffects.isPlayerFreezing(player), "stopping clears exposure");
            player.removeAllEffects();
            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(!SnowStormEffects.freezePlayer(player), "creative players are excluded");
            helper.assertTrue(player.getActiveEffects().isEmpty(), "creative player gets no fallback slowness");
            player.setGameMode(GameType.SPECTATOR);
            helper.assertTrue(!SnowStormEffects.freezePlayer(player), "spectators are excluded");
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(!staleExposure.isActive(helper.getLevel()), "unrefreshed exposure expires");
                helper.succeed();
            });
        } finally {
            SnowStormEffects.stopFreezingPlayer(player);
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    static ServerPlayer makePlayer(GameTestHelper helper, String name) {
        var server = helper.getLevel().getServer();
        var profile = new GameProfile(UUID.nameUUIDFromBytes(("naturaldisaster:" + name).getBytes(StandardCharsets.UTF_8)), name);
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // Mock connections skip negotiation. Advertise packets used during login and temperature sync.
        var channels = ChannelAttributes.getOrCreateAdHocChannels(connection);
        channels.add(ResourceLocation.fromNamespaceAndPath(NaturalDisaster.MODID, "disaster_render"));
        if (NaturalDisaster.COLD_SWEAT_LOADED) {
            for (String id : new String[]{"sync_config_settings", "sync_temperature", "sync_temp_modifiers",
                    "sync_forge_data", "sync_item_predicates", "sync_container_slot", "particle_batch",
                    "disable_hearth_particles", "hearth_reset", "block_data_update", "chameleon_eat",
                    "play_entity_attached_sound", "sync_shearable_data", "entity_mount"}) {
                channels.add(ResourceLocation.fromNamespaceAndPath("cold_sweat", id));
            }
        }
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var pos = helper.absolutePos(new net.minecraft.core.BlockPos(3, 3, 3));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.setNoGravity(true);
        player.setGameMode(GameType.SURVIVAL);
        // placeNewPlayer may restore a previous run's player data for these deterministic IDs.
        player.removeAllEffects();
        player.getInventory().clearContent();
        player.setTicksFrozen(0);
        player.clearFire();
        player.setHealth(player.getMaxHealth());
        return player;
    }
}
