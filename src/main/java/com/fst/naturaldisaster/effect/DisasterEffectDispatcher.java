package com.fst.naturaldisaster.effect;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Aggregates player delivery after all levels have evaluated coverage. No event state is kept. */
public final class DisasterEffectDispatcher {
    private final Map<UUID, AppliedPlayer> applied = new HashMap<>();

    public void tick(MinecraftServer server, Predicate<ServerPlayer> snowStormCoverage) {
        applied.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AppliedPlayer previous = applied.get(player.getUUID());
            DisasterRenderState visual = player.getData(PlayerDisasterData.RENDER_STATE);
            boolean covered = player.isAlive() && snowStormCoverage.test(player);
            if (!covered) {
                if (previous != null || visual.isRendering()) {
                    DisasterEffects.setDisasterRendering(player, false);
                    applied.remove(player.getUUID());
                }
                continue;
            }
            boolean changed = !visual.isRendering() || previous == null || previous.player() != player
                    || !previous.dimension().equals(player.level().dimension());
            if (changed) {
                DisasterEffects.setDisasterRendering(player, true);
                applied.put(player.getUUID(), new AppliedPlayer(player, player.level().dimension()));
            }
            if ((changed || server.getTickCount() % 20 == 0) && !player.isCreative() && !player.isSpectator()
                    && player.serverLevel().isPositionEntityTicking(player.blockPosition())
                    && DisasterEffects.isPlayerExposed(player.getUUID())) {
                SnowStormEffects.freezePlayer(player);
            }
        }
    }

    public void clear() {
        applied.clear();
    }

    private record AppliedPlayer(ServerPlayer player, ResourceKey<Level> dimension) {
    }
}
