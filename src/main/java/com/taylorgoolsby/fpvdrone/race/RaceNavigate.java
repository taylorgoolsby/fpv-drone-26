package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.race.db.RaceRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Racing: entering and leaving a track, detecting gate passes and timing laps.
 *
 * A lap is timed from the moment gate 0 is passed until gate 0 is passed again,
 * after every other gate. Everything here runs on the server thread. Database
 * work is asynchronous and its results come back on the server thread.
 */
public final class RaceNavigate {
    private static final int LEADERBOARD_SIZE = 10;
    /** Laps longer than this are discarded. */
    private static final long MAX_LAP_MILLIS = 16_777_215L;

    /** userId -> the track they are racing. */
    private static final Map<String, RaceTrack> userToTrack = new HashMap<>();
    /** Tracks that are loaded, because someone is racing them. */
    private static final Map<BlockKey, RaceTrack> startToTrack = new HashMap<>();
    private static final Map<BlockKey, ArrayList<String>> startToUsers = new HashMap<>();
    private static final Map<String, double[]> prevUserPos = new HashMap<>();
    private static final Map<String, Integer> userNextGate = new HashMap<>();
    private static final Map<String, Long> userStartTime = new HashMap<>();
    /** Users whose track is currently loading. Prevents double submission. */
    private static final Set<String> loading = new HashSet<>();

    private RaceNavigate() {}

    /** Check if a user is in racing mode. */
    public static boolean checkRacingMode(String userId) {
        return userToTrack.containsKey(userId);
    }

    /**
     * The player right clicked a target block with an empty hand.
     * If the target block is a registered track start, enter that track,
     * or leave it if the player is already racing it.
     */
    public static void handleRightClickTrack(String userId, String dimension, BlockPos startingPos,
                                             BiPredicate<String, BlockPos> checkSolid, ServerPlayer player) {
        if (!RaceServer.databaseReady) {
            RaceServer.databaseUnavailable(player);
            return;
        }
        if (loading.contains(userId)) return;

        if (checkRacingMode(userId)) {
            RaceTrack current = userToTrack.get(userId);
            if (current.dimension.equals(dimension) && current.getStartPos().equals(startingPos)) {
                // Right clicked the starting block of the track they are already in.
                exitRacingMode(userId, player, null);
                return;
            }
            // A different track. First leave the previous one.
            exitRacingMode(userId, player, null);
        }

        BlockKey startKey = new BlockKey(dimension, startingPos);
        RaceTrack loaded = startToTrack.get(startKey);
        if (loaded != null) {
            enterRacingMode(userId, loaded, startKey, player);
            return;
        }

        loading.add(userId);
        RaceServer.then(RaceRepository.getTrackWithGates(dimension, startingPos),
                track -> {
                    loading.remove(userId);
                    if (track == null) return; // an ordinary target block
                    if (player.isRemoved() || checkRacingMode(userId)) return;

                    // Another racer may have loaded it while we were waiting.
                    RaceTrack existing = startToTrack.get(startKey);
                    if (existing != null) {
                        enterRacingMode(userId, existing, startKey, player);
                        return;
                    }
                    try {
                        track.computeGates(checkSolid);
                    } catch (RaceGateException e) {
                        RaceServer.error(player, e.endUserReason);
                        return;
                    }
                    if (track.gates.size() < 2) {
                        RaceServer.error(player, "This track has fewer than 2 gates.");
                        return;
                    }
                    startToTrack.put(startKey, track);
                    enterRacingMode(userId, track, startKey, player);
                },
                error -> {
                    loading.remove(userId);
                    RaceServer.error(player, "Unable to load the track. " + error.getMessage());
                });
    }

    private static void enterRacingMode(String userId, RaceTrack track, BlockKey startKey, ServerPlayer player) {
        // Assign the user to the track:
        userToTrack.put(userId, track);
        userNextGate.put(userId, 0);
        userStartTime.remove(userId);
        prevUserPos.put(userId, new double[]{player.getX(), player.getY(), player.getZ()});
        startToUsers.computeIfAbsent(startKey, k -> new ArrayList<>()).add(userId);

        RaceServer.info(player, "You are racing track '" + track.name + "'.");
        RaceNet.sendRaceStart(player, track);
        RaceNet.sendNextGate(player, 0);
        RaceWaypoints.show(player, track.gates.get(0));

        // The best times of everyone (including self):
        sendLeaderboard(track, player);
    }

    private static void sendLeaderboard(RaceTrack track, ServerPlayer only) {
        RaceServer.then(RaceRepository.getBestTimes(track.raceTrackId, LEADERBOARD_SIZE),
                laps -> {
                    if (only != null) {
                        if (checkRacingMode(RaceServer.getId(only.getUUID()))) RaceNet.sendLeaderboard(only, laps);
                        return;
                    }
                    ArrayList<String> users = startToUsers.get(new BlockKey(track.dimension, track.getStartPos()));
                    if (users == null) return;
                    for (String id : new ArrayList<>(users)) {
                        ServerPlayer p = RaceServer.getPlayer(id);
                        if (p != null) RaceNet.sendLeaderboard(p, laps);
                    }
                },
                error -> {});
    }

    /**
     * @param player may be null if the player is no longer online
     * @param reason null for a voluntary exit, otherwise why the player was removed
     */
    public static void exitRacingMode(String userId, ServerPlayer player, String reason) {
        RaceTrack track = userToTrack.get(userId);
        if (track == null) return; // The user isn't assigned to a track.

        BlockKey startKey = new BlockKey(track.dimension, track.getStartPos());

        userToTrack.remove(userId);
        userNextGate.remove(userId);
        userStartTime.remove(userId);
        prevUserPos.remove(userId);

        // Unload the track if no one is using it:
        ArrayList<String> users = startToUsers.get(startKey);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) {
                startToUsers.remove(startKey);
                startToTrack.remove(startKey);
            }
        }

        if (player != null) {
            if (reason == null) {
                RaceServer.info(player, "You left racing mode.");
            } else {
                RaceServer.error(player, "You were kicked out of racing mode because: " + reason);
            }
            RaceNet.sendRaceStop(player);
            RaceWaypoints.hide(player);
        }
    }

    public static void exitAllPlayersFromTrack(BlockKey key, String reason) {
        ArrayList<String> userIds = startToUsers.get(key);
        if (userIds == null) return; // No one is racing the track.
        for (String userId : new ArrayList<>(userIds)) {
            exitRacingMode(userId, RaceServer.getPlayer(userId), reason);
        }
    }

    /**
     * A block changed. If it is inside the bounding box of a gate of a track that
     * someone is racing, those racers are kicked out. Only loaded tracks need to be
     * checked, because nobody else is affected, and a track is validated again
     * every time it is loaded.
     */
    public static void changedGateBlock(String dimension, BlockPos pos, String reason) {
        if (startToTrack.isEmpty()) return;
        for (Map.Entry<BlockKey, RaceTrack> entry : new ArrayList<>(startToTrack.entrySet())) {
            RaceTrack track = entry.getValue();
            if (!track.dimension.equals(dimension) || track.gates == null) continue;
            for (RaceGate gate : track.gates) {
                if (gate.dimension.equals(dimension) && gate.containsBlock(pos)) {
                    exitAllPlayersFromTrack(entry.getKey(), reason);
                    break;
                }
            }
        }
    }

    /** Called every tick for every player who is racing. */
    public static void onPlayerMoved(String userId, String dimension, double[] playerPos, ServerPlayer player) {
        // prevUserPos is initialized in enterRacingMode.
        double[] hit = new double[3];
        int loopCount = 0;
        boolean passedGate;
        do {
            passedGate = checkUserPassedGate(userId, dimension, playerPos, hit);
            if (passedGate) {
                // Advance the target gate and handle lap completion:
                handleGatePassed(userId, player);

                // Advancing the gate allows the next call to checkUserPassedGate to check
                // whether the user also passed through the next gate, and so on,
                // until the user fails to pass through a gate.
                prevUserPos.put(userId, hit.clone());
            }
            loopCount++;
        } while (passedGate && checkRacingMode(userId) && loopCount <= RaceBuild.MAX_GATES_PER_TRACK);

        if (checkRacingMode(userId)) prevUserPos.put(userId, playerPos);
    }

    private static boolean checkUserPassedGate(String userId, String dimension, double[] playerPos, double[] intersectionOut) {
        double[] prev = prevUserPos.get(userId);
        if (prev == null) return false;
        RaceGate nextGate = getNextGate(userId);
        if (nextGate == null) return false;
        return GateCrossing.passedThrough(nextGate, dimension, prev, playerPos, intersectionOut);
    }

    private static RaceGate getNextGate(String userId) {
        RaceTrack track = userToTrack.get(userId);
        if (track == null || track.gates == null) return null;
        Integer gateIndex = userNextGate.get(userId);
        if (gateIndex == null || gateIndex < 0 || gateIndex >= track.gates.size()) return null;
        return track.gates.get(gateIndex);
    }

    private static void handleGatePassed(String userId, ServerPlayer player) {
        RaceTrack track = userToTrack.get(userId);
        Integer gateIndex = userNextGate.get(userId);
        if (track == null || track.gates == null || gateIndex == null) return;

        int maxIndex = track.gates.size() - 1;
        int nextIndex = gateIndex + 1;
        if (nextIndex > maxIndex) nextIndex = 0;
        userNextGate.put(userId, nextIndex);

        // Update the client's HUD and locator bar:
        RaceNet.sendNextGate(player, nextIndex);
        RaceWaypoints.show(player, track.gates.get(nextIndex));

        boolean quiet = RaceServer.isFlying(player.getUUID()); // the HUD shows it already

        if (gateIndex == 0) {
            // The first gate was passed.
            if (userStartTime.get(userId) == null) {
                // Start the timer for the first time.
                userStartTime.put(userId, System.currentTimeMillis());
                if (!quiet) {
                    RaceServer.info(player, "Passed gate " + (gateIndex + 1) + " of " + track.gates.size() + ".");
                }
                RaceNet.sendLapStart(player, -1);
            } else {
                handleLapComplete(userId, track, player, quiet);
            }
        } else if (!quiet) {
            RaceServer.info(player, "Passed gate " + (gateIndex + 1) + " of " + track.gates.size() + ".");
        }
    }

    private static void handleLapComplete(String userId, RaceTrack track, ServerPlayer player, boolean quiet) {
        long endMillis = System.currentTimeMillis();
        long elapsed = endMillis - userStartTime.get(userId);
        userStartTime.put(userId, endMillis); // the next lap starts right now

        if (elapsed > MAX_LAP_MILLIS) {
            RaceNet.sendLapStart(player, -1);
            RaceServer.error(player, "Lap discarded. Took too long.");
            return;
        }

        RaceNet.sendLapStart(player, (int) elapsed);

        if (!quiet) {
            RaceServer.success(player, "Lap time: " + RaceTime.format(elapsed));
        }

        // Store the time, then refresh everyone's leaderboard:
        RaceServer.then(
                RaceRepository.insertLap(track.raceTrackId, userId, player.getPlainTextName(), (int) elapsed, RaceServer.VERSION),
                lapId -> sendLeaderboard(track, null),
                error -> RaceServer.error(player, "Unable to save your lap time. " + error.getMessage()));
    }

    /** Server shutdown. */
    public static void clear() {
        userToTrack.clear();
        startToTrack.clear();
        startToUsers.clear();
        prevUserPos.clear();
        userNextGate.clear();
        userStartTime.clear();
        loading.clear();
    }
}
