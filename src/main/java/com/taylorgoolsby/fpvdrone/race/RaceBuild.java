package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.race.db.RaceRepository;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Track building.
 *
 * First the player places the target block. That puts them in building mode.
 * Then they go around the track and right click each gate's interior face
 * with a stick, in the order the gates are to be flown. /race setName names the
 * track. Finally they right click the target block with the stick to save it.
 *
 * At any time they can break the target block to exit building mode.
 *
 * Everything here runs on the server thread. Database work is asynchronous and
 * its results come back on the server thread through RaceServer.then.
 */
public final class RaceBuild {
    public static final int MAX_GATES_PER_TRACK = 150;

    private static final Map<String, BlockKey> startingBlock = new HashMap<>();
    private static final Map<String, RaceGate[]> gateChoices = new HashMap<>();
    private static final Map<String, ArrayList<RaceGate>> completedGates = new HashMap<>();
    private static final Map<String, String> trackName = new HashMap<>();

    private RaceBuild() {}

    /** Check if a user is in building mode. */
    public static boolean checkBuildingMode(String userId) {
        return startingBlock.containsKey(userId);
    }

    public static void startTrack(String userId, String dimension, BlockPos pos, ServerPlayer player) {
        if (!RaceServer.databaseReady) {
            RaceServer.databaseUnavailable(player);
            return;
        }
        if (checkBuildingMode(userId)) {
            RaceServer.error(player, "You are already building a track. Break its target block to start over.");
            return;
        }
        startingBlock.put(userId, new BlockKey(dimension, pos));
        gateChoices.remove(userId);
        completedGates.put(userId, new ArrayList<>());
        trackName.put(userId, "");

        RaceServer.info(player, "You are now building a track. Right click gates with a stick to add them, "
                + "in the order they are to be flown.");
        RaceNet.sendBuildMode(player, true);
    }

    public static void exitBuildingMode(String userId, ServerPlayer player) {
        // Allowed even if the user is not in building mode. It doesn't hurt anything,
        // and presents an opportunity to clear memory.
        boolean wasBuilding = startingBlock.remove(userId) != null;
        gateChoices.remove(userId);
        completedGates.remove(userId);
        trackName.remove(userId);

        if (wasBuilding && player != null) {
            RaceServer.info(player, "You are no longer building a track.");
            RaceNet.sendBuildMode(player, false);
        }
    }

    public static void setTrackName(String userId, String name, ServerPlayer player) {
        if (name.isEmpty()) {
            RaceServer.error(player, "Track name cannot be blank.");
            return;
        }
        String sanitizedName = Ids.sanitizeName(name);
        if (!sanitizedName.equals(name)) {
            RaceServer.error(player, "Track name can only contain standard keyboard characters, "
                    + "at most 32 of them. Space and backslash are not allowed.");
            return;
        }

        RaceServer.then(RaceRepository.getTrackByName(sanitizedName),
                existing -> {
                    if (!checkBuildingMode(userId)) return; // left building mode meanwhile
                    if (existing != null) {
                        RaceServer.error(player, "That name is already taken.");
                    } else {
                        trackName.put(userId, sanitizedName);
                        RaceServer.success(player, "Track name set to '" + sanitizedName + "'.");
                    }
                },
                error -> RaceServer.error(player, "Unable to check the track name: " + error.getMessage()));
    }

    /**
     * Handles a stick right click on a block while in building mode.
     *
     * @param pos      the clicked block
     * @param testFace the clicked face (it must be the gate's interior face)
     */
    public static void addBlock(String userId, String dimension, BlockPos pos, Direction testFace,
                                BiPredicate<String, BlockPos> checkSolid, ServerPlayer player) {
        if (testFace == null) return;
        if (!checkBuildingMode(userId)) return;

        BlockKey blockKey = new BlockKey(dimension, pos);
        ArrayList<RaceGate> currentCompletedGates = completedGates.get(userId);
        BlockKey startKey = startingBlock.get(userId);

        // Back at the starting block. This means the target block was right clicked again.
        if (startKey.equals(blockKey)) {
            completeTrack(userId, player);
            return;
        }

        if (!dimension.equals(startKey.dimension)) {
            RaceServer.error(player, "Gates must be in the same dimension as the target block.");
            return;
        }

        if (currentCompletedGates.size() >= MAX_GATES_PER_TRACK) {
            RaceServer.error(player, "The max number of gates has been reached (" + MAX_GATES_PER_TRACK + ").");
            return;
        }

        if (!checkSolid.test(dimension, pos)) {
            RaceServer.error(player, "Gate block must be solid.");
            return;
        }

        // If there are stored choices, then the clicked block is being used to choose one.
        RaceGate[] storedChoices = gateChoices.get(userId);
        if (storedChoices != null) {
            boolean found1 = storedChoices[0].path.contains(pos);
            boolean found2 = storedChoices[1].path.contains(pos);
            gateChoices.remove(userId);
            if (found1 == found2) {
                RaceServer.error(player, "The block you chose does not specify which gate you want to add.");
                return;
            }
            addGate(currentCompletedGates, found1 ? storedChoices[0] : storedChoices[1], player);
            return;
        }

        // Otherwise, there are no stored choices:
        ArrayList<BlockPos> neighbors = findSolidNeighbors(pos, testFace, dimension, checkSolid);
        if (neighbors.isEmpty()) {
            RaceServer.error(player, "Either you selected the wrong face, or this is not a gate.");
            return;
        }

        if (neighbors.size() == 1) {
            try {
                RaceGate gate = RaceMath.loadGate(dimension, pos, testFace, neighbors.get(0), checkSolid, null);
                addGate(currentCompletedGates, gate, player);
            } catch (RaceGateException e) {
                RaceServer.error(player, "Failed to add a gate. " + e.endUserReason);
            }
            return;
        }

        // Two possible planes.
        RaceGate gate1 = null;
        RaceGate gate2 = null;
        RaceGateException e1 = null;
        RaceGateException e2 = null;
        try {
            gate1 = RaceMath.loadGate(dimension, pos, testFace, neighbors.get(0), checkSolid, null);
        } catch (RaceGateException e) {
            e1 = e;
        }
        try {
            gate2 = RaceMath.loadGate(dimension, pos, testFace, neighbors.get(1), checkSolid, null);
        } catch (RaceGateException e) {
            e2 = e;
        }

        if (gate1 != null && gate2 == null) {
            addGate(currentCompletedGates, gate1, player);
        } else if (gate2 != null && gate1 == null) {
            addGate(currentCompletedGates, gate2, player);
        } else if (gate1 != null) {
            gateChoices.put(userId, new RaceGate[]{gate1, gate2});
            RaceServer.error(player, "Two possible gates were found. "
                    + "Choose another block to specify which one you want to add.");
        } else {
            String reason = "";
            if (!e1.endUserReason.isEmpty() && !e2.endUserReason.isEmpty()) {
                reason = "Either: " + e1.endUserReason + " OR " + e2.endUserReason;
            } else if (!e1.endUserReason.isEmpty()) {
                reason = e1.endUserReason;
            } else if (!e2.endUserReason.isEmpty()) {
                reason = e2.endUserReason;
            }
            RaceServer.error(player, "Failed to add a gate. " + reason);
        }
    }

    private static void addGate(ArrayList<RaceGate> gates, RaceGate gate, ServerPlayer player) {
        gates.add(gate);
        RaceServer.success(player, "Gate " + gates.size() + " successfully added.");
        RaceNet.sendBuildGate(player, gate);
    }

    public static void completeTrack(String userId, ServerPlayer player) {
        if (!checkBuildingMode(userId)) return;
        BlockKey startKey = startingBlock.get(userId);
        ArrayList<RaceGate> gates = completedGates.get(userId);
        String name = trackName.get(userId);

        if (gates.size() < 2) {
            RaceServer.error(player, "The track must contain at least 2 gates.");
            return;
        }
        if (name.isEmpty()) {
            RaceServer.error(player, "The track is unnamed. Set it using /race setName <name>.");
            return;
        }

        // Building mode is exited before the asynchronous save in order to avoid double submission.
        exitBuildingMode(userId, player);

        RaceServer.then(
                RaceRepository.insertTrackWithGates(userId, name, startKey.dimension, startKey.pos, gates),
                raceTrackId -> RaceServer.success(player, "Track '" + name + "' successfully built!"),
                error -> RaceServer.error(player, "Unable to save the track. " + error.getMessage()));
    }

    /** The target block at pos was broken. */
    public static void removeTrack(String userId, String dimension, BlockPos pos, ServerPlayer player) {
        // Only exit building mode if the broken block is the track currently being created.
        BlockKey start = startingBlock.get(userId);
        if (start != null && pos.equals(start.pos) && dimension.equals(start.dimension)) {
            exitBuildingMode(userId, player);
        }

        if (!RaceServer.databaseReady) return;
        RaceServer.then(RaceRepository.softDeleteTrackAt(dimension, pos),
                track -> {
                    if (track == null) return; // it was just a target block, not a track
                    // Remove the breaker from the race first:
                    RaceNavigate.exitRacingMode(userId, player, null);

                    // Kick out all the other racers
                    String reason = "The track starting point was removed by " + player.getPlainTextName();
                    RaceNavigate.exitAllPlayersFromTrack(new BlockKey(track.dimension, track.getStartPos()), reason);

                    if (userId.equals(track.ownerUserId)) {
                        RaceServer.success(player, "Your track '" + track.name + "' was removed.");
                    } else {
                        RaceServer.info(player, "The track '" + track.name + "' was removed.");
                    }
                },
                error -> RaceServer.error(player, "Unable to remove the track. " + error.getMessage()));
    }

    /**
     * Finds the solid blocks neighboring pos which could continue a gate whose
     * interior face is `face`. At most one per plane, so 0, 1 or 2 results.
     */
    private static ArrayList<BlockPos> findSolidNeighbors(BlockPos pos, Direction face, String dimension,
                                                          BiPredicate<String, BlockPos> checkSolid) {
        // In valid gates, all the blocks touch either face to face or edge to edge.
        // Corner touches are not valid.
        //
        // If the block has 0 or 1 neighbors, it's definitely an error.
        // If it has 2 or more, the neighbors are sorted into planes with the air block
        // designated by the face. If there is just 1 plane, any neighbor in it can be used.
        // If there are 2 planes, a helper block is needed to determine which one to use.
        //
        // The lowest tier (touching only the solid block's edge, away from the air) is not
        // considered because only convex gates are accepted. Likewise, the block behind the
        // clicked block is not acceptable.

        // Figure out local up and right directions
        Direction up;
        Direction right;
        if (face == Direction.UP || face == Direction.DOWN) {
            up = Direction.SOUTH;
            right = Direction.EAST;
        } else if (face == Direction.NORTH || face == Direction.SOUTH) {
            up = Direction.UP;
            right = Direction.EAST;
        } else {
            up = Direction.UP;
            right = Direction.SOUTH;
        }
        Direction down = up.getOpposite();
        Direction left = right.getOpposite();

        // Tier 0: touching the air block face to face and the solid block edge to edge.
        // Tier 1: touching the solid block face to face and the air block edge to edge.
        BlockPos topUp = firstSolid(dimension, checkSolid, pos.relative(up).relative(face), pos.relative(up));
        BlockPos topRight = firstSolid(dimension, checkSolid, pos.relative(right).relative(face), pos.relative(right));
        BlockPos topDown = firstSolid(dimension, checkSolid, pos.relative(down).relative(face), pos.relative(down));
        BlockPos topLeft = firstSolid(dimension, checkSolid, pos.relative(left).relative(face), pos.relative(left));

        // Gather only 1 block per plane:
        ArrayList<BlockPos> result = new ArrayList<>();
        if (topUp != null || topDown != null) {
            result.add(topUp != null ? topUp : topDown);
        }
        if (topRight != null || topLeft != null) {
            result.add(topRight != null ? topRight : topLeft);
        }
        return result;
    }

    private static BlockPos firstSolid(String dimension, BiPredicate<String, BlockPos> checkSolid, BlockPos... tiers) {
        for (BlockPos p : tiers) {
            if (checkSolid.test(dimension, p)) return p;
        }
        return null;
    }

    /** Server shutdown. */
    public static void clear() {
        startingBlock.clear();
        gateChoices.clear();
        completedGates.clear();
        trackName.clear();
    }
}
