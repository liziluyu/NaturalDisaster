package com.fst.naturaldisaster.command;

import com.fst.naturaldisaster.effect.DisasterEffects;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /disaster debug ...}: exercises the generic disaster effects without a disaster event.
 *
 * <p>Every effect can target an explicit player selector or defaults to the executing player.
 * Client-side effects are still server-authoritative: the command validates the arguments and
 * sends the payload, while the client keeps and restores its own previous state.
 */
public final class DisasterDebugCommands {
    private static final int TICKS_PER_SECOND = 20;
    private static final int MIN_DURATION_SECONDS = 1;
    private static final int MAX_DURATION_SECONDS = 86_400;

    private DisasterDebugCommands() {
    }

    /** Builds the {@code debug} subtree; the caller attaches it below {@code /disaster}. */
    public static LiteralArgumentBuilder<CommandSourceStack> root(CommandBuildContext buildContext) {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .then(viewDistance())
                .then(effect(buildContext))
                .then(fog());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> viewDistance() {
        return Commands.literal("viewdistance")
                .then(Commands.literal("reset")
                        .executes(context -> resetViewDistance(context, defaultTargets(context)))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> resetViewDistance(context,
                                        EntityArgument.getPlayers(context, "targets")))))
                .then(Commands.argument("chunks", IntegerArgumentType.integer(
                                DisasterEffects.MIN_VIEW_DISTANCE, DisasterEffects.MAX_VIEW_DISTANCE))
                        .executes(context -> setViewDistance(context, defaultTargets(context),
                                IntegerArgumentType.getInteger(context, "chunks")))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> setViewDistance(context,
                                        EntityArgument.getPlayers(context, "targets"),
                                        IntegerArgumentType.getInteger(context, "chunks")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> effect(CommandBuildContext buildContext) {
        return Commands.literal("effect")
                .then(Commands.argument("effect", ResourceArgument.resource(buildContext, Registries.MOB_EFFECT))
                        .then(Commands.argument("durationSeconds", IntegerArgumentType.integer(
                                        MIN_DURATION_SECONDS, MAX_DURATION_SECONDS))
                                .executes(context -> applyEffect(context, defaultTargets(context),
                                        effect(context), 0))
                                .then(Commands.argument("amplifier", IntegerArgumentType.integer(
                                                MobEffectInstance.MIN_AMPLIFIER, MobEffectInstance.MAX_AMPLIFIER))
                                        .executes(context -> applyEffect(context, defaultTargets(context),
                                                effect(context),
                                                IntegerArgumentType.getInteger(context, "amplifier")))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(context -> applyEffect(context,
                                                        EntityArgument.getPlayers(context, "targets"),
                                                        effect(context),
                                                        IntegerArgumentType.getInteger(context, "amplifier")))))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(context -> applyEffect(context,
                                                EntityArgument.getPlayers(context, "targets"),
                                                effect(context), 0)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> fog() {
        return Commands.literal("fog")
                .then(Commands.literal("clear")
                        .executes(context -> clearFogColor(context, defaultTargets(context)))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> clearFogColor(context,
                                        EntityArgument.getPlayers(context, "targets")))))
                .then(Commands.argument("red", IntegerArgumentType.integer(
                                DisasterEffects.MIN_FOG_COMPONENT, DisasterEffects.MAX_FOG_COMPONENT))
                        .then(Commands.argument("green", IntegerArgumentType.integer(
                                        DisasterEffects.MIN_FOG_COMPONENT, DisasterEffects.MAX_FOG_COMPONENT))
                                .then(Commands.argument("blue", IntegerArgumentType.integer(
                                                DisasterEffects.MIN_FOG_COMPONENT, DisasterEffects.MAX_FOG_COMPONENT))
                                        .executes(context -> setFogColor(context, defaultTargets(context)))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(context -> setFogColor(context,
                                                        EntityArgument.getPlayers(context, "targets")))))));
    }

    private static Collection<ServerPlayer> defaultTargets(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return List.of(context.getSource().getPlayerOrException());
    }

    private static Holder.Reference<MobEffect> effect(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return ResourceArgument.getMobEffect(context, "effect");
    }

    private static int setViewDistance(CommandContext<CommandSourceStack> context,
                                       Collection<ServerPlayer> targets, int chunks) {
        for (ServerPlayer player : targets) {
            DisasterEffects.setViewDistance(player, chunks);
        }
        int count = targets.size();
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Set client view distance to %d chunk(s) for %d player(s); "
                        + "run /disaster debug viewdistance reset to restore it.",
                chunks, count)), false);
        return count;
    }

    private static int resetViewDistance(CommandContext<CommandSourceStack> context,
                                         Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            DisasterEffects.resetViewDistance(player);
        }
        int count = targets.size();
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Restored the original client view distance for %d player(s).", count)), false);
        return count;
    }

    private static int applyEffect(CommandContext<CommandSourceStack> context,
                                   Collection<ServerPlayer> targets, Holder.Reference<MobEffect> effect,
                                   int amplifier) {
        int durationSeconds = IntegerArgumentType.getInteger(context, "durationSeconds");
        int durationTicks = durationSeconds * TICKS_PER_SECOND;
        int applied = 0;
        List<String> rejected = new ArrayList<>();
        for (ServerPlayer player : targets) {
            if (DisasterEffects.applyMobEffect(player, effect, durationTicks, amplifier)) {
                applied++;
            } else {
                rejected.add(player.getGameProfile().getName());
            }
        }

        String effectId = effect.key().location().toString();
        if (applied == 0) {
            context.getSource().sendFailure(Component.literal("No target accepted " + effectId
                    + " (amplifier " + amplifier + ", " + durationSeconds + " s); "
                    + "a stronger effect of the same type may already be active."));
            return 0;
        }

        int count = applied;
        String suffix = rejected.isEmpty() ? "" : "; rejected by " + String.join(", ", rejected);
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Applied %s (amplifier %d) for %d s to %d player(s)%s.",
                effectId, amplifier, durationSeconds, count, suffix)), false);
        return count;
    }

    private static int setFogColor(CommandContext<CommandSourceStack> context,
                                   Collection<ServerPlayer> targets) {
        int red = IntegerArgumentType.getInteger(context, "red");
        int green = IntegerArgumentType.getInteger(context, "green");
        int blue = IntegerArgumentType.getInteger(context, "blue");
        for (ServerPlayer player : targets) {
            DisasterEffects.setFogColor(player, red, green, blue);
        }
        int count = targets.size();
        String color = String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue);
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Overrode client fog colour to %s for %d player(s); "
                        + "run /disaster debug fog clear to restore it.",
                color, count)), false);
        return count;
    }

    private static int clearFogColor(CommandContext<CommandSourceStack> context,
                                     Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            DisasterEffects.clearFogColor(player);
        }
        int count = targets.size();
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Restored vanilla fog rendering for %d player(s).", count)), false);
        return count;
    }
}
