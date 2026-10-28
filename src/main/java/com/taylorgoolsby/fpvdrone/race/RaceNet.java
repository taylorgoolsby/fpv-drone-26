package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.DroneNetwork;
import com.taylorgoolsby.fpvdrone.race.net.BuildGatePacket;
import com.taylorgoolsby.fpvdrone.race.net.GateBox;
import com.taylorgoolsby.fpvdrone.race.net.GateIndexPacket;
import com.taylorgoolsby.fpvdrone.race.net.LapStartPacket;
import com.taylorgoolsby.fpvdrone.race.net.LeaderboardEntry;
import com.taylorgoolsby.fpvdrone.race.net.LeaderboardPacket;
import com.taylorgoolsby.fpvdrone.race.net.RaceModePacket;
import com.taylorgoolsby.fpvdrone.race.net.SetBuildModePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Server -> client senders for the race packets. */
public final class RaceNet {
    private RaceNet() {}

    private static void send(ServerPlayer player, Object packet) {
        if (player == null) return;
        DroneNetwork.CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    public static GateBox box(RaceGate gate) {
        return new GateBox(
                gate.origin.getX(), gate.origin.getY(), gate.origin.getZ(),
                gate.farthest.getX(), gate.farthest.getY(), gate.farthest.getZ());
    }

    public static void sendBuildMode(ServerPlayer player, boolean active) {
        send(player, new SetBuildModePacket(active));
    }

    public static void sendBuildGate(ServerPlayer player, RaceGate gate) {
        send(player, new BuildGatePacket(gate.dimension, box(gate)));
    }

    public static void sendRaceStart(ServerPlayer player, RaceTrack track) {
        List<GateBox> boxes = new ArrayList<>(track.gates.size());
        for (RaceGate gate : track.gates) boxes.add(box(gate));
        send(player, new RaceModePacket(true, track.name, track.dimension, boxes));
    }

    public static void sendRaceStop(ServerPlayer player) {
        send(player, RaceModePacket.stop());
    }

    public static void sendNextGate(ServerPlayer player, int index) {
        send(player, new GateIndexPacket(index));
    }

    public static void sendLapStart(ServerPlayer player, int lastLapMillis) {
        send(player, new LapStartPacket(lastLapMillis));
    }

    public static void sendLeaderboard(ServerPlayer player, List<RaceLap> laps) {
        List<LeaderboardEntry> entries = new ArrayList<>(laps.size());
        for (RaceLap lap : laps) {
            entries.add(new LeaderboardEntry(Ids.fromHex(lap.userId), lap.userName, lap.millis));
        }
        send(player, new LeaderboardPacket(entries));
    }
}
