package com.taylorgoolsby.fpvdrone.race;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.taylorgoolsby.fpvdrone.race.db.RaceRepository;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import java.util.Set;

/**
 * /race setName <name>   name the track you are building
 * /race list             list the names of all race tracks
 * /race tp <name>       teleport to a track's target block
 * /race delete <name>   delete a track (operators only)
 */
public final class RaceCommand {
    private RaceCommand() {}

    /** Suggests the names of existing tracks while typing /race tp or /race delete. */
    private static final SuggestionProvider<CommandSourceStack> TRACK_NAMES = (context, builder) -> {
        if (!RaceServer.databaseReady) return builder.buildFuture();
        return RaceRepository.listTrackNames().thenApply(names -> {
            for (String name : names) builder.suggest(name);
            return builder.build();
        });
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> race = Commands.literal("race");
        race.executes(RaceCommand::help);
        race.then(Commands.literal("setName").then(
                Commands.argument("name", StringArgumentType.string()).executes(RaceCommand::setName)));
        race.then(Commands.literal("list").executes(RaceCommand::list));
        race.then(Commands.literal("tp").then(
                Commands.argument("name", StringArgumentType.string())
                        .suggests(TRACK_NAMES)
                        .executes(RaceCommand::teleport)));
        race.then(Commands.literal("delete")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // same level as /gamemode
                .then(
                        Commands.argument("name", StringArgumentType.string())
                                .suggests(TRACK_NAMES)
                                .executes(RaceCommand::delete)));
        dispatcher.register(race);
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Manage race tracks with this command.\n"
                        + "/race setName <name> - Give a name to the track you are currently building.\n"
                        + "/race list - List the names of all race tracks.\n"
                        + "/race tp <name> - Teleport to a race track.\n"
                        + "/race delete <name> - Delete a race track (operators only)."), false);
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

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        requireDatabase();
        CommandSourceStack source = context.getSource();

        RaceServer.then(RaceRepository.listTrackNames(),
                names -> {
                    if (names.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("There are no race tracks yet."), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                "Race tracks (" + names.size() + "): " + String.join(", ", names)), false);
                    }
                },
                error -> source.sendFailure(Component.literal("Unable to list the tracks. " + error.getMessage())));
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        requireDatabase();
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        String who = source.getTextName();

        RaceServer.then(RaceRepository.softDeleteTrackByName(name),
                track -> {
                    if (track == null) {
                        source.sendFailure(Component.literal("There is no race track named '" + name
                                + "'. Use /race list to see them."));
                        return;
                    }
                    // Everyone racing it is taken out of racing mode:
                    RaceNavigate.exitAllPlayersFromTrack(new BlockKey(track.dimension, track.getStartPos()),
                            "The track was deleted by " + who);
                    source.sendSuccess(() -> Component.literal("Track '" + track.name + "' was deleted."), true);
                },
                error -> source.sendFailure(Component.literal("Unable to delete the track. " + error.getMessage())));
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        requireDatabase();
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();

        RaceServer.then(RaceRepository.getTrackByName(name),
                track -> {
                    if (track == null) {
                        source.sendFailure(Component.literal("There is no race track named '" + name
                                + "'. Use /race list to see them."));
                        return;
                    }

                    MinecraftServer server = source.getServer();
                    ServerLevel destination = null;
                    for (ServerLevel level : server.getAllLevels()) {
                        if (RaceServer.dimensionOf(level).equals(track.dimension)) {
                            destination = level;
                            break;
                        }
                    }
                    if (destination == null) {
                        source.sendFailure(Component.literal("The dimension of track '" + track.name
                                + "' (" + track.dimension + ") is not available."));
                        return;
                    }

                    // Leaving a track by teleporting would otherwise count as flying through gates.
                    RaceNavigate.exitRacingMode(RaceServer.getId(player.getUUID()), player, null);

                    // Stand on top of the target block, in the middle of it.
                    player.teleportTo(destination,
                            track.startPosX + 0.5, track.startPosY + 1.0, track.startPosZ + 0.5,
                            Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
                    RaceServer.success(player, "Teleported to track '" + track.name + "'.");
                },
                error -> source.sendFailure(Component.literal("Unable to look up the track. " + error.getMessage())));
        return 1;
    }

    private static void requireDatabase() throws CommandSyntaxException {
        if (!RaceServer.databaseReady) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "Racing is unavailable because the race database could not be opened. "
                            + RaceServer.databaseError)).create();
        }
    }
}