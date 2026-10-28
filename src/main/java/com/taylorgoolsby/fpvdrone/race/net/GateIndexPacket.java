package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Server -> client. Index of the gate the racer must fly through next.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record GateIndexPacket(int nextGate) {

    public static final StreamCodec<RegistryFriendlyByteBuf, GateIndexPacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.nextGate()),
                    buf -> new GateIndexPacket(buf.readVarInt())
            );

    public static void handle(GateIndexPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> RaceClientState.setNextGate(msg.nextGate()));
        ctx.setPacketHandled(true);
    }
}
