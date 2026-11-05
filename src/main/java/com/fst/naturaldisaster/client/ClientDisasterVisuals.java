package com.fst.naturaldisaster.client;

import com.fst.naturaldisaster.NaturalDisaster;
import com.fst.naturaldisaster.effect.PlayerDisasterData;
import com.fst.naturaldisaster.network.DisasterRenderPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Local player's transient visual transition. Never changes server coverage or gameplay. */
@EventBusSubscriber(modid = NaturalDisaster.MODID, value = Dist.CLIENT)
public final class ClientDisasterVisuals {
    private static LocalPlayer owner;
    private static ClientLevel world;

    private ClientDisasterVisuals() {
    }

    public static void handle(DisasterRenderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null
                    || !minecraft.level.dimension().location().equals(payload.dimension())) {
                return;
            }
            bind(minecraft.player, minecraft.level);
            owner.getData(PlayerDisasterData.RENDER_STATE).setRendering(payload.rendering());
        });
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        bind(minecraft.player, minecraft.level);
        owner.getData(PlayerDisasterData.RENDER_STATE).tick();
    }

    public static float intensity(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        return owner != null && owner == minecraft.player && world == minecraft.level
                ? owner.getData(PlayerDisasterData.RENDER_STATE).intensity(partialTick) : 0f;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        float alpha = intensity((float) event.getPartialTick());
        if (alpha > 0f) {
            event.setRed(mix(event.getRed(), 210f / 255f, alpha));
            event.setGreen(mix(event.getGreen(), 220f / 255f, alpha));
            event.setBlue(mix(event.getBlue(), 230f / 255f, alpha));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fogDistance(ViewportEvent.RenderFog event) {
        float alpha = intensity((float) event.getPartialTick());
        if (alpha <= 0f || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        // A continuous visual distance avoids integer option changes and rebuilding chunks.
        float end = Math.min(event.getFarPlaneDistance(), 3 * 16f);
        float start = Math.min(event.getNearPlaneDistance(), end * 0.25f);
        event.setFarPlaneDistance(mix(event.getFarPlaneDistance(), end, alpha));
        event.setNearPlaneDistance(mix(event.getNearPlaneDistance(), start, alpha));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void bind(LocalPlayer player, ClientLevel level) {
        if (owner != player || world != level) {
            clear();
            player.getData(PlayerDisasterData.RENDER_STATE).reset();
            owner = player;
            world = level;
        }
    }

    private static void clear() {
        if (owner != null) {
            owner.getData(PlayerDisasterData.RENDER_STATE).reset();
        }
        owner = null;
        world = null;
    }

    private static float mix(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }
}
