package com.taylorgoolsby.fpvdrone.race;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class RaceCommand {
    private RaceCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> race = Commands.literal("race");

        race.executes(RaceCommand::help);
        race.then(Commands.literal("setName").then(
                Commands.argument("name", StringArgumentType.word()).executes(RaceCommand::setName)));

        dispatcher.register(race);
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Manage race tracks with this command.\n"
                        + "/race setName <name> - Give a name to the track you are currently building."), false);
        return 1;
    }

    private static int setName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer caller = context.getSource().getPlayerOrException();
        String userId = RaceServer.getId(caller.getUUID());

        if (!RaceBuild.checkBuildingMode(userId)) {
            throw new SimpleCommandExceptionType(Component.literal("You are not in track building mode.")).create();
        }

        String name = StringArgumentType.getString(context, "name");

        // The result (name taken, name set, ...) is reported in chat when the
        // database lookup finishes.
        RaceBuild.setTrackName(userId, name, caller);
        return 1;
    }
}
