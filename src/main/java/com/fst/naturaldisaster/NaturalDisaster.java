package com.fst.naturaldisaster;

import com.fst.naturaldisaster.command.DisasterCommands;
import com.fst.naturaldisaster.disaster.BiomeDisasterTypes;
import com.fst.naturaldisaster.network.DisasterNetwork;
import com.fst.naturaldisaster.service.DisasterService;
import com.fst.naturaldisaster.service.DisasterSyncService;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * Entry point of the Natural Disaster mod.
 *
 * <p>Everything about disaster centers is a pure function of (seed, gameTime, dimension, position),
 * so this class holds no center state: it only wires event listeners and the config spec.
 */
@Mod(NaturalDisaster.MODID)
public final class NaturalDisaster {
    /** Must match {@code mod_id} in gradle.properties and META-INF/neoforge.mods.toml. */
    public static final String MODID = "naturaldisaster";

    private static final Logger LOGGER = LogUtils.getLogger();

    public NaturalDisaster(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(DisasterNetwork::register);

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(DisasterService.INSTANCE);
        NeoForge.EVENT_BUS.register(DisasterSyncService.INSTANCE);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Natural Disaster common setup: centersPerKm2={}, kappa={}, layers={}, disasterDurationSeconds={}, disasterRadiusChunks={}, surfaceDisasterTickSpeed={}, randomDisasterTickSpeed={}",
                Config.INSTANCE.centersPerKm2.get(), Config.INSTANCE.kappa.get(),
                Config.INSTANCE.layers.get(), Config.INSTANCE.disasterDurationSeconds.get(),
                Config.INSTANCE.disasterRadiusChunks.get(),
                Config.INSTANCE.surfaceDisasterTickSpeed.get(), Config.INSTANCE.randomDisasterTickSpeed.get());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        BiomeDisasterTypes.rebuild(event.getServer().registryAccess(), Config.INSTANCE.biomeDisasterTypes.get());
        LOGGER.info("Natural Disaster attached to server instance");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DisasterCommands.register(event);
    }
}
