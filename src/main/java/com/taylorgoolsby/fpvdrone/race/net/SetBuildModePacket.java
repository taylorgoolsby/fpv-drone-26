package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Server -> client. The player entered or left track building mode.
 * Leaving clears the gate boxes the client was drawing.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record SetBuildModePacket(boolean active) {

    public static final StreamCodec<RegistryFriendlyByteBuf, SetBuildModePacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeBoolean(msg.active()),
                    buf -> new SetBuildModePacket(buf.readBoolean())
            );

    public static void handle(SetBuildModePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> RaceClientState.setBuildMode(msg.active()));
        ctx.setPacketHandled(true);
    }
}
