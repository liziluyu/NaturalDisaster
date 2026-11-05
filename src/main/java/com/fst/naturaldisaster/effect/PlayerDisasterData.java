package com.fst.naturaldisaster.effect;

import com.fst.naturaldisaster.NaturalDisaster;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Session-only player data, deliberately without a serializer or copy-on-death. */
public final class PlayerDisasterData {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, NaturalDisaster.MODID);
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<DisasterRenderState>> RENDER_STATE =
            TYPES.register("disaster_render_state", () -> AttachmentType.builder(DisasterRenderState::new).build());

    private PlayerDisasterData() {
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
