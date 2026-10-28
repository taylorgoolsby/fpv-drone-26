package com.taylorgoolsby.fpvdrone.race.net;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client. The fastest lap of each player on the track the receiver is racing,
 * fastest first.
 *
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record LeaderboardPacket(List<LeaderboardEntry> entries) {

    public static final StreamCodec<RegistryFriendlyByteBuf, LeaderboardPacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.entries().size());
                        for (LeaderboardEntry e : msg.entries()) {
                            buf.writeUUID(e.userId());
                            buf.writeUtf(e.name());
                            buf.writeInt(e.millis());
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<LeaderboardEntry> entries = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            entries.add(new LeaderboardEntry(buf.readUUID(), buf.readUtf(), buf.readInt()));
                        }
                        return new LeaderboardPacket(entries);
                    }
            );

    public static void handle(LeaderboardPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> RaceClientState.setLeaderboard(msg.entries()));
        ctx.setPacketHandled(true);
    }
}
