package com.fst.naturaldisaster.client;

import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.client.xaero.XaeroDisasterMapIntegration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client-side bridge between the mod and the optional Xaero integration.
 *
 * <p>This is the only place that decides whether the Xaero-only class may be touched. When Xaero is
 * absent the guard short-circuits before the integration class is loaded, so the mod still starts
 * normally without the optional dependency.
 */
@EventBusSubscriber(modid = NaturalDisaster.MODID, value = Dist.CLIENT)
public final class XaeroDisasterEvents {
    private static final String XAERO_WORLD_MAP = "xaeroworldmap";

    private XaeroDisasterEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ModList.get().isLoaded(XAERO_WORLD_MAP)) {
            XaeroDisasterMapIntegration.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDisasterState.clear();
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        // A dimension change must not leave a stale overlay from the previous level.
        ClientDisasterState.clear();
    }
}
