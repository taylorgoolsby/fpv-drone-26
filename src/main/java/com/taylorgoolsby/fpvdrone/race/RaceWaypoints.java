package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.waypoints.Waypoint;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Puts the next gate on the vanilla locator bar (added in 1.21.6).
 *
 * The server sends the client a tracked waypoint at a fixed position. The
 * locator bar renders any waypoint the client knows about, so this does not
 * depend on the game rule or on the player transmitting anything. Each racer
 * has one waypoint id. Sending the waypoint again moves it, and removing it
 * takes it off the bar.
 *
 * NOTE: this is the one class that talks to vanilla's waypoint classes.
 * If a vanilla signature differs in 26.3, this is the only file to fix.
 */
public final class RaceWaypoints {
    /** Gold, to stand out from the green/white of player waypoints. RGB, no alpha. */
    private static final int COLOR = 0xFFD166;

    private static final Map<UUID, UUID> WAYPOINT_IDS = new HashMap<>();

    private RaceWaypoints() {}

    /** Shows (or moves) the racer's next-gate waypoint. */
    public static void show(ServerPlayer player, RaceGate gate) {
        V3 c = gate.getCenter();
        Vec3i pos = new Vec3i(Math.round(c.x), Math.round(c.y), Math.round(c.z));

        UUID id = WAYPOINT_IDS.computeIfAbsent(player.getUUID(), k -> UUID.randomUUID());
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.color = Optional.of(COLOR);
        player.connection.send(ClientboundTrackedWaypointPacket.addWaypointPosition(id, icon, pos));
    }

    /** Removes the racer's waypoint, if any. */
    public static void hide(ServerPlayer player) {
        if (player == null) return;
        UUID id = WAYPOINT_IDS.remove(player.getUUID());
        if (id == null) return;
        player.connection.send(ClientboundTrackedWaypointPacket.removeWaypoint(id));
    }

    /** The player logged out; there is nobody to send a packet to. */
    public static void forget(UUID playerUuid) {
        WAYPOINT_IDS.remove(playerUuid);
    }

    /** Server shutdown. */
    public static void clear() {
        WAYPOINT_IDS.clear();
    }
}
