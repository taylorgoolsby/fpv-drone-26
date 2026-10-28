package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.function.BiPredicate;

public class RaceTrack {
    public String raceTrackId;
    public String ownerUserId;
    public String name;
    public String dimension;
    public int startPosX;
    public int startPosY;
    public int startPosZ;
    public boolean deleted;
    public long dateUpdated;
    public long dateCreated;

    // Derived:
    public ArrayList<RaceGate> gates;

    public BlockPos getStartPos() {
        return new BlockPos(startPosX, startPosY, startPosZ);
    }

    /**
     * Recomputes every gate's boundaries from the world. Call on the server thread.
     *
     * @throws RaceGateException whose endUserReason mentions the broken gate.
     */
    public void computeGates(BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
        for (RaceGate gate : this.gates) {
            try {
                gate.computeBoundaries(checkSolid);
            } catch (RaceGateException e) {
                String where = gate.a == null ? "" : "\nCheck near [" + gate.a.getX() + ", " + gate.a.getY() + ", " + gate.a.getZ() + "].";
                throw new RaceGateException(e.getMessage(), "The track has broken gates. " + e.endUserReason + where);
            }
        }
    }

    @Override
    public int hashCode() { return raceTrackId.hashCode(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RaceTrack t)) return false;
        return raceTrackId.equals(t.raceTrackId);
    }
}
