package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client. The player started or stopped racing a track.
 * When starting, it carries the track name and every gate, in flying order.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record RaceModePacket(boolean racing, String trackName, String dimension, List<GateBox> gates) {

    public static RaceModePacket stop() {
        return new RaceModePacket(false, "", "", List.of());
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RaceModePacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeBoolean(msg.racing());
                        buf.writeUtf(msg.trackName());
                        buf.writeUtf(msg.dimension());
                        buf.writeVarInt(msg.gates().size());
                        for (GateBox g : msg.gates()) g.write(buf);
                    },
                    buf -> {
                        boolean racing = buf.readBoolean();
                        String name = buf.readUtf();
                        String dimension = buf.readUtf();
                        int n = buf.readVarInt();
                        List<GateBox> gates = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) gates.add(GateBox.read(buf));
                        return new RaceModePacket(racing, name, dimension, gates);
                    }
            );

    public static void handle(RaceModePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (msg.racing()) {
                RaceClientState.startRacing(msg.trackName(), msg.dimension(), msg.gates());
            } else {
                RaceClientState.stopRacing();
            }
        });
        ctx.setPacketHandled(true);
    }
}
