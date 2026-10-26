package com.taylorgoolsby.fpvdrone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.UUID;

/**
 * Server -> client. Tells a client that a player entered or left drone mode,
 * so every client's DroneRegistry matches the server's. Without it, other
 * players would still look like ordinary players.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record DroneStatePacket(UUID player, boolean active) {

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneStatePacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeUUID(msg.player());
                        buf.writeBoolean(msg.active());
                    },
                    buf -> new DroneStatePacket(buf.readUUID(), buf.readBoolean())
            );

    public static void handle(DroneStatePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            DroneRegistry.set(msg.player(), msg.active());
            if (!msg.active()) {
                DroneOrientations.remove(msg.player());
            }
        });
        ctx.setPacketHandled(true);
    }
}