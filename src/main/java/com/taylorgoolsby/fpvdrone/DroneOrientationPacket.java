package com.taylorgoolsby.fpvdrone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Client -> server. The sender's drone camera orientation as a quaternion,
 * sent every client tick while flying. The server checks it and relays it to
 * the players tracking the sender (see DroneOrientationBroadcastPacket).
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record DroneOrientationPacket(float x, float y, float z, float w) {

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneOrientationPacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeFloat(msg.x());
                        buf.writeFloat(msg.y());
                        buf.writeFloat(msg.z());
                        buf.writeFloat(msg.w());
                    },
                    buf -> new DroneOrientationPacket(
                            buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat()
                    )
            );

    public static void handle(DroneOrientationPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;

            // Only drone pilots have an orientation to share.
            if (!DroneRegistry.isDrone(sender.getUUID())) {
                DroneDebug.log("server-reject-" + sender.getUUID(),
                        "server: orientation from " + sender.getName().getString()
                                + " ignored, not a drone on the server");
                return;
            }

            // Don't pass garbage on to other clients.
            float lengthSquared = msg.x() * msg.x() + msg.y() * msg.y()
                    + msg.z() * msg.z() + msg.w() * msg.w();
            if (!Float.isFinite(lengthSquared) || lengthSquared < 1e-6f) return;

            DroneDebug.log("server-relay-" + sender.getUUID(),
                    "server: relaying orientation from " + sender.getName().getString()
                            + " (" + msg.x() + ", " + msg.y() + ", " + msg.z() + ", " + msg.w() + ")");
            DroneNetwork.relayOrientation(sender, msg.x(), msg.y(), msg.z(), msg.w());
        });
        ctx.setPacketHandled(true);
    }
}