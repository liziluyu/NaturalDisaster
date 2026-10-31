package com.fst.naturaldisaster.client.xaero;

import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;

/**
 * Lazily registers the disaster overlay with Xaero's world map.
 *
 * <p>Only reached after the client event guard has confirmed that {@code xaeroworldmap} is loaded.
 * Xaero creates its handler during second-stage client startup, so registration waits for
 * {@link WorldMap#loaded}. {@code add(...)} has no unregister counterpart, so the handler instance
 * is remembered and each handler is populated exactly once.
 */
public final class XaeroDisasterMapIntegration {
    private static MapElementRenderHandler registeredHandler;

    private XaeroDisasterMapIntegration() {
    }

    public static void tick() {
        MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
        if (!WorldMap.loaded || handler == null || handler == registeredHandler) {
            return;
        }
        handler.add(DisasterChunkRenderer.create());
        registeredHandler = handler;
    }
}
