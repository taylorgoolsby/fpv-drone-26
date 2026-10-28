package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Server -> client, during track building. A gate was registered; draw a box around it.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record BuildGatePacket(String dimension, GateBox gate) {

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildGatePacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeUtf(msg.dimension());
                        msg.gate().write(buf);
                    },
                    buf -> new BuildGatePacket(buf.readUtf(), GateBox.read(buf))
            );

    public static void handle(BuildGatePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> RaceClientState.addBuildGate(msg.dimension(), msg.gate()));
        ctx.setPacketHandled(true);
    }
}
