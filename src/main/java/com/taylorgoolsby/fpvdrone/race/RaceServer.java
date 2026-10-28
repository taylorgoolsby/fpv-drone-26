package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.DroneRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Shared server-side helpers for the racing feature: the running server,
 * hopping from the database thread back to the server thread, chat messages
 * and world queries.
 */
public final class RaceServer {
    /** Written into each lap row. */
    public static final String VERSION = "26.3";

    public static volatile MinecraftServer server;
    public static volatile boolean databaseReady = false;
    /** Why the database could not be opened, for chat messages. */
    public static volatile String databaseError = "";

    private RaceServer() {}

    /** Tells the player racing is unavailable, including the reason. */
    public static void databaseUnavailable(ServerPlayer player) {
        error(player, "Racing is unavailable because the race database could not be opened. " + databaseError);
    }

    // ---------------------------------------------------------------- threading

    /**
     * When the future completes (on any thread), runs the matching callback on
     * the server thread. If the server has stopped in the meantime, nothing runs.
     */
    public static <T> void then(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> {
            MinecraftServer s = server;
            if (s == null) return;
            s.execute(() -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    onError.accept(cause);
                } else {
                    onSuccess.accept(value);
                }
            });
        });
    }

    // ------------------------------------------------------------------- ids

    public static String getId(UUID uuid) {
        return Ids.toHex(uuid);
    }

    public static ServerPlayer getPlayer(String userId) {
        MinecraftServer s = server;
        if (s == null) return null;
        return s.getPlayerList().getPlayer(Ids.fromHex(userId));
    }

    // ------------------------------------------------------------- world access

    public static String dimensionOf(Level level) {
        return level.dimension().identifier().toString();
    }

    /** Solid-block test for a single level. Blocks in other dimensions are never solid. */
    public static BiPredicate<String, BlockPos> solidChecker(Level level) {
        String dimension = dimensionOf(level);
        return (testDimension, testPos) -> testDimension.equals(dimension) && level.getBlockState(testPos).isSolid();
    }

    // ---------------------------------------------------------------- messages

    /** Players who are flying a drone get less chat spam; their HUD shows the same info. */
    public static boolean isFlying(UUID uuid) {
        return DroneRegistry.isDrone(uuid);
    }

    public static void info(ServerPlayer player, String message) {
        send(player, message, ChatFormatting.YELLOW);
    }

    public static void error(ServerPlayer player, String message) {
        if (message != null && message.startsWith("java.lang.Exception: ")) {
            message = message.substring(21);
        }
        send(player, message, ChatFormatting.RED);
    }

    public static void success(ServerPlayer player, String message) {
        send(player, message, ChatFormatting.GREEN);
    }

    private static void send(ServerPlayer player, String message, ChatFormatting color) {
        if (player == null || message == null) return;
        player.sendSystemMessage(Component.literal(message).withStyle(color));
    }
}
