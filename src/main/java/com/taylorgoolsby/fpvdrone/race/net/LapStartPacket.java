package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Server -> client. A lap just started: the first gate was passed, or the previous lap was completed.
 *
 * No timestamp is sent. The client starts its HUD timer when the packet arrives,
 * so differences between the server's and client's clocks do not matter. The
 * time that is saved to the database is always measured by the server.
 *
 * @param lastLapMillis the lap that was just completed, or -1 when this is the first lap
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record LapStartPacket(int lastLapMillis) {

    public static final StreamCodec<RegistryFriendlyByteBuf, LapStartPacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeInt(msg.lastLapMillis()),
                    buf -> new LapStartPacket(buf.readInt())
            );

    public static void handle(LapStartPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> RaceClientState.startLap(msg.lastLapMillis()));
        ctx.setPacketHandled(true);
    }
}
