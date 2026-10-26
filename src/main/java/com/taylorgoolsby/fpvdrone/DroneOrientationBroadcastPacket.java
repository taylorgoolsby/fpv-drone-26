package com.taylorgoolsby.fpvdrone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.UUID;

/**
 * Server -> client. Another player's drone camera orientation, relayed from
 * their DroneOrientationPacket to the players tracking them.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record DroneOrientationBroadcastPacket(UUID player, float x, float y, float z, float w) {

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneOrientationBroadcastPacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeUUID(msg.player());
                        buf.writeFloat(msg.x());
                        buf.writeFloat(msg.y());
                        buf.writeFloat(msg.z());
                        buf.writeFloat(msg.w());
                    },
                    buf -> new DroneOrientationBroadcastPacket(
                            buf.readUUID(),
                            buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat()
                    )
            );

    public static void handle(DroneOrientationBroadcastPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            // Ignore stragglers that arrive after the player has landed.
            boolean drone = DroneRegistry.isDrone(msg.player());
            DroneDebug.markReceived(msg.player());
            DroneDebug.log("client-recv-" + msg.player(),
                    "client: orientation for " + msg.player()
                            + " registryDrone=" + drone
                            + " (" + msg.x() + ", " + msg.y() + ", " + msg.z() + ", " + msg.w() + ")");
            if (drone) {
                DroneOrientations.set(msg.player(), msg.x(), msg.y(), msg.z(), msg.w());
            }
        });
        ctx.setPacketHandled(true);
    }
}