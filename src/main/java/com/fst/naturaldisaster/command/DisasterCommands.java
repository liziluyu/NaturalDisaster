package com.fst.naturaldisaster.command;

import com.fst.naturaldisaster.disaster.BiomeDisasterTypes;
import com.fst.naturaldisaster.disaster.DisasterType;
import com.fst.naturaldisaster.disaster.DisasterTypes;
import com.fst.naturaldisaster.field.CenterHit;
import com.fst.naturaldisaster.field.CenterPosition;
import com.fst.naturaldisaster.field.DisasterField;
import com.fst.naturaldisaster.field.EventState;
import com.fst.naturaldisaster.service.DisasterService;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /disaster ison}: report the disaster state of the command source's current chunk.
 *
 * <p>{@code /disaster locate [type]}: scan a bounded neighbourhood of the deterministic center grid
 * and report the nearest running disaster center, optionally filtered by its declared type id.
 *
 * <p>{@code /disaster debug ...} (see {@link DisasterDebugCommands}) exercises the generic effect
 * primitives without a disaster event.
 */
public final class DisasterCommands {
    private static final int MAX_REPORTED = 16;
    /** Maximum anchor-grid rings inspected by {@code locate}; independent of world size. */
    private static final int LOCATE_RINGS = 24;
    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown disaster type: " + id));

    private DisasterCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("disaster")
                        .then(Commands.literal("ison")
                                .executes(DisasterCommands::execute))
                        .then(Commands.literal("locate")
                                .executes(DisasterCommands::locate)
                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                                DisasterTypes.all().stream().map(DisasterType::id), builder))
                                        .executes(DisasterCommands::locateTyped)))
                        .then(DisasterDebugCommands.root(event.getBuildContext())));
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
                    " - center (%d, %d) at (%d, %d), phase %s, radius %d chunk(s), effect %s, %d s left",
                    hit.gridX(), hit.gridZ(), Math.round(hit.x()), Math.round(hit.z()),
                    event.phase(), event.radiusChunks(), event.effectActive() ? "on" : "off",
                    remainingSeconds)), false);
        }
        if (active.size() > shown) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    " ... and %d more", active.size() - shown)), false);
        }
        return active.size();
    }

    /**
     * Reports the nearest center whose pure event is active. The scan covers a fixed number of
     * anchor-grid rings around the command source, so its cost is bounded by density and never by
     * world size or elapsed time. It does not require chunks to be loaded and never force-loads.
     */
    private static int locate(CommandContext<CommandSourceStack> context) {
        return locate(context, null);
    }

    private static int locateTyped(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var id = ResourceLocationArgument.getId(context, "type");
        if (!DisasterTypes.isKnown(id)) {
            throw UNKNOWN_TYPE.create(id);
        }
        return locate(context, DisasterTypes.byId(id));
    }

    private static int locate(CommandContext<CommandSourceStack> context, DisasterType requestedType) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        long tick = level.getGameTime();
        DisasterService.LevelState state = DisasterService.INSTANCE.state(level);
        DisasterField field = state.field();
        // Dummy can also arise from an unkeyed/unknown biome, independently of configured mappings.
        if (requestedType != null && !DisasterTypes.DUMMY.equals(requestedType)
                && !BiomeDisasterTypes.typeByBiome().containsValue(requestedType)) {
            source.sendFailure(Component.literal("No biome is mapped to disaster type " + requestedType.id() + "."));
            return 0;
        }

        double playerX = source.getPosition().x;
        double playerZ = source.getPosition().z;
        double spacing = field.params().gridSpacing();
        int centerI = (int) Math.floor(playerX / spacing);
        int centerJ = (int) Math.floor(playerZ / spacing);

        Nearest nearest = new Nearest();
        consider(nearest, state, requestedType, tick, centerI, centerJ, playerX, playerZ);
        for (int ring = 1; ring <= LOCATE_RINGS; ring++) {
            int minI = centerI - ring;
            int maxI = centerI + ring;
            int minJ = centerJ - ring;
            int maxJ = centerJ + ring;

            for (int i = minI; i <= maxI; i++) {
                consider(nearest, state, requestedType, tick, i, minJ, playerX, playerZ);
                consider(nearest, state, requestedType, tick, i, maxJ, playerX, playerZ);
            }
            for (int j = minJ + 1; j < maxJ; j++) {
                consider(nearest, state, requestedType, tick, minI, j, playerX, playerZ);
                consider(nearest, state, requestedType, tick, maxI, j, playerX, playerZ);
            }
        }

        if (nearest.event == null) {
            double scannedBlocks = (LOCATE_RINGS + 1) * spacing;
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "No active disaster%s within %d center-grid steps (~%d blocks) of (%d, %d).",
                    requestedType == null ? "" : " of type " + requestedType.id(), LOCATE_RINGS,
                    Math.round(scannedBlocks), Math.round(playerX), Math.round(playerZ))));
            return 0;
        }

        CenterHit hit = nearest.hit;
        EventState event = nearest.event;
        DisasterType type = requestedType != null ? requestedType
                : state.typeForEvent(hit.gridX(), hit.gridZ(), event.startTick());
        long remainingSeconds = (event.remaining() + 19L) / 20L;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Nearest active disaster: type %s, center (%d, %d) at (%d, %d), %d block(s) away, "
                        + "phase %s, radius %d chunk(s), effect %s, %d s left.",
                type.id(), hit.gridX(), hit.gridZ(), Math.round(hit.x()), Math.round(hit.z()),
                Math.round(Math.sqrt(nearest.distanceSquared)), event.phase(), event.radiusChunks(),
                event.effectActive() ? "on" : "off", remainingSeconds)), false);
        return 1;
    }

    private static void consider(Nearest nearest, DisasterService.LevelState state, DisasterType requestedType, long tick,
                                 int i, int j, double x, double z) {
        DisasterField field = state.field();
        EventState event = field.event(tick, i, j);
        if (!event.active()) {
            return;
        }
        CenterPosition position = field.centerPosition(tick, i, j);
        double dx = position.x() - x;
        double dz = position.z() - z;
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared < nearest.distanceSquared) {
            if (requestedType != null && !requestedType.equals(state.typeForEvent(i, j, event.startTick()))) {
                return;
            }
            nearest.hit = new CenterHit(i, j, position.x(), position.z(), event.radiusChunks());
            nearest.event = event;
            nearest.distanceSquared = distanceSquared;
        }
    }

    private static final class Nearest {
        private CenterHit hit;
        private EventState event;
        private double distanceSquared = Double.POSITIVE_INFINITY;
    }

    private record ActiveHit(CenterHit hit, EventState event) {
    }
}
