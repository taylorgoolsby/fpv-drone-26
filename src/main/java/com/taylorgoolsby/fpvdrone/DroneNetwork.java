package com.taylorgoolsby.fpvdrone;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.UUID;

public final class DroneNetwork {

    // 2: added drone state and orientation sync for other players.
    private static final int PROTOCOL_VERSION = 2;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(FpvDroneMod.MODID, "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    private DroneNetwork() {}

    /** Call once from the mod constructor, on both client and server. */
    public static void register() {
        CHANNEL.play()
                .serverbound(flow -> flow
                        .add(DroneTogglePacket.class, DroneTogglePacket.CODEC, DroneTogglePacket::handle)
                        .add(DroneOrientationPacket.class, DroneOrientationPacket.CODEC, DroneOrientationPacket::handle)
                )
                .clientbound(flow -> flow
                        .add(DroneStatePacket.class, DroneStatePacket.CODEC, DroneStatePacket::handle)
                        .add(DroneOrientationBroadcastPacket.class, DroneOrientationBroadcastPacket.CODEC, DroneOrientationBroadcastPacket::handle)
                );
    }

    // ---- Client side ----

    /** Tell the server we entered or left drone mode. */
    public static void sendToggle(boolean active) {
        CHANNEL.send(new DroneTogglePacket(active), PacketDistributor.SERVER.noArg());
    }

    /** Tell the server where our drone camera is pointing (a quaternion). */
    public static void sendOrientation(float x, float y, float z, float w) {
        CHANNEL.send(new DroneOrientationPacket(x, y, z, w), PacketDistributor.SERVER.noArg());
    }

    // ---- Server side ----

    /** Tell every client that a player entered or left drone mode. */
    public static void broadcastState(UUID player, boolean active) {
        CHANNEL.send(new DroneStatePacket(player, active), PacketDistributor.ALL.noArg());
    }

    /** Tell one client that a player is in (or out of) drone mode. */
    public static void sendStateTo(ServerPlayer target, UUID player, boolean active) {
        CHANNEL.send(new DroneStatePacket(player, active), PacketDistributor.PLAYER.with(target));
    }

    /** Pass a pilot's camera orientation on to the players who can see them. */
    public static void relayOrientation(ServerPlayer pilot, float x, float y, float z, float w) {
        CHANNEL.send(
                new DroneOrientationBroadcastPacket(pilot.getUUID(), x, y, z, w),
                PacketDistributor.TRACKING_ENTITY.with(pilot)
        );
    }
}