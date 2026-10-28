package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.race.net.GateBox;
import com.taylorgoolsby.fpvdrone.race.net.LeaderboardEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * What the local client currently knows about racing, written by the race
 * packets and read by the HUD and the gate renderer.
 *
 * Must stay free of client-only imports: the packet classes call into it, and
 * they load on dedicated servers. Lists are replaced, never mutated, so the
 * render code can read them without locking.
 */
public final class RaceClientState {
    private RaceClientState() {}

    // ---- track building ----
    public static volatile boolean buildMode = false;
    public static volatile String buildDimension = "";
    public static volatile List<GateBox> buildGates = List.of();

    // ---- racing ----
    public static volatile boolean racing = false;
    public static volatile String trackName = "";
    public static volatile String dimension = "";
    public static volatile List<GateBox> gates = List.of();
    public static volatile int nextGate = 0;
    /** System.currentTimeMillis() when the current lap started, or 0 if the first gate has not been passed yet. */
    public static volatile long lapStartMillis = 0;
    /** The most recently completed lap, or -1. */
    public static volatile int lastLapMillis = -1;
    public static volatile List<LeaderboardEntry> leaderboard = List.of();

    public static void setBuildMode(boolean active) {
        buildMode = active;
        buildGates = List.of();
        buildDimension = "";
    }

    public static void addBuildGate(String dim, GateBox gate) {
        if (!dim.equals(buildDimension)) {
            buildGates = List.of();
            buildDimension = dim;
        }
        List<GateBox> next = new ArrayList<>(buildGates);
        next.add(gate);
        buildGates = List.copyOf(next);
    }

    public static void startRacing(String name, String dim, List<GateBox> trackGates) {
        racing = true;
        trackName = name;
        dimension = dim;
        gates = List.copyOf(trackGates);
        nextGate = 0;
        lapStartMillis = 0;
        lastLapMillis = -1;
        leaderboard = List.of();
    }

    public static void stopRacing() {
        racing = false;
        trackName = "";
        dimension = "";
        gates = List.of();
        nextGate = 0;
        lapStartMillis = 0;
        lastLapMillis = -1;
        leaderboard = List.of();
    }

    public static void setNextGate(int index) {
        nextGate = index;
    }

    public static void startLap(int completedLapMillis) {
        lapStartMillis = System.currentTimeMillis();
        if (completedLapMillis >= 0) lastLapMillis = completedLapMillis;
    }

    public static void setLeaderboard(List<LeaderboardEntry> entries) {
        leaderboard = List.copyOf(entries);
    }

    /** The gate to fly through next, or null. */
    public static GateBox getNextGateBox() {
        List<GateBox> g = gates;
        int i = nextGate;
        if (!racing || i < 0 || i >= g.size()) return null;
        return g.get(i);
    }

    /** Called when leaving the world or server. */
    public static void reset() {
        setBuildMode(false);
        stopRacing();
    }
}
