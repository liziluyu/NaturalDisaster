package com.fst.naturaldisaster.command;

import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.service.DisasterService;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /disaster ison}: report the disaster state of the command source's current chunk.
 */
public final class DisasterCommands {
    private static final int MAX_REPORTED = 16;

    private DisasterCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("disaster")
                        .then(Commands.literal("ison")
                                .executes(DisasterCommands::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ChunkPos chunk = new ChunkPos(BlockPos.containing(source.getPosition()));
        long tick = level.getGameTime();

        DisasterService.LevelState state = DisasterService.INSTANCE.state(level);
        DisasterService.ChunkStatus status = state.status(chunk.toLong(), tick);
        if (status == null) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "Chunk (%d, %d) is not loaded; disasters only affect loaded chunks.",
                    chunk.x, chunk.z)), false);
            return 0;
        }
        DisasterField field = state.field();

        List<ActiveHit> active = new ArrayList<>(status.hits().size());
        for (CenterHit hit : status.hits()) {
            EventState event = field.event(tick, hit.gridX(), hit.gridZ());
            if (event.active()) {
                active.add(new ActiveHit(hit, event));
            }
        }

        if (status.hits().isEmpty()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "No disaster center covers chunk (%d, %d).", chunk.x, chunk.z)), false);
            return 0;
        }

        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "No active disaster in chunk (%d, %d); %d center(s) cover it but are idle.",
                    chunk.x, chunk.z, status.hits().size())), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Active disaster(s) in chunk (%d, %d): %d", chunk.x, chunk.z, active.size())), false);

        int shown = Math.min(active.size(), MAX_REPORTED);
        for (int index = 0; index < shown; index++) {
            ActiveHit entry = active.get(index);
            CenterHit hit = entry.hit();
            EventState event = entry.event();
            long remainingSeconds = (event.remaining() + 19L) / 20L;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    " - center (%d, %d) at (%d, %d), radius %d chunk(s), %d s left",
                    hit.gridX(), hit.gridZ(), Math.round(hit.x()), Math.round(hit.z()),
                    hit.radiusChunks(), remainingSeconds)), false);
        }
        if (active.size() > shown) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    " ... and %d more", active.size() - shown)), false);
        }
        return active.size();
    }

    private record ActiveHit(CenterHit hit, EventState event) {
    }
}
