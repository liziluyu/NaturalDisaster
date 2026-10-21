package com.fst.naturaldisaster;

import com.fst.naturaldisaster.command.DisasterCommands;
import com.fst.naturaldisaster.service.DisasterService;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point of the Natural Disaster mod.
 *
 * <p>Everything about disaster centers is a pure function of (seed, gameTime, dimension, position),
 * so this class holds no center state: it only wires event listeners and the config spec.
 */
@Mod(NaturalDisaster.MODID)
public final class NaturalDisaster {
    /** Must match {@code mod_id} in gradle.properties and META-INF/mods.toml. */
    public static final String MODID = "naturaldisaster";

    private static final Logger LOGGER = LogUtils.getLogger();

    public NaturalDisaster(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(DisasterService.INSTANCE);

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Natural Disaster common setup: centersPerKm2={}, kappa={}, layers={}, litDuration={}, disasterDurationSeconds={}, disasterRadiusChunks={}",
                Config.INSTANCE.centersPerKm2.get(), Config.INSTANCE.kappa.get(),
                Config.INSTANCE.layers.get(), Config.INSTANCE.litDuration.get(),
                Config.INSTANCE.disasterDurationSeconds.get(), Config.INSTANCE.disasterRadiusChunks.get());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Natural Disaster attached to server instance");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        DisasterCommands.register(event);
    }
}
