package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.function.BiPredicate;

/**
 * A single gate of a track.
 *
 * What is persisted: dimension, a, face, b, origin, farthest.
 * What is derived by computeBoundaries(): rowMin/rowMax, right/up/normal, path.
 */
public class RaceGate {
    // Persisted; they come from Minecraft:
    public String dimension;
    public BlockPos a;
    public Direction face;
    public BlockPos b;

    // Database:
    public String raceTrackId;
    public int index;

    // Persisted and indexed for finding a gate, but also set by computeBoundaries():
    public BlockPos origin;   // The bottom-left corner of the AABB (more negative)
    public BlockPos farthest; // The top-right corner of the AABB (more positive)

    // Not persisted. Null until computeBoundaries() is called:
    public int[] rowMin; // left most block (solid) for a given row
    public int[] rowMax; // right most block (solid) for a given row
    public V3 right;
    public V3 up;
    public V3 normal;
    public ArrayList<BlockPos> path;

    public RaceGate(String dimension, BlockPos a, Direction face, BlockPos b) {
        this.dimension = dimension;
        this.a = a;
        this.face = face;
        this.b = b;
    }

    /** Re-derives the boundaries from the world. Call on the server thread. */
    public void computeBoundaries(BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
        if (this.dimension == null || this.a == null || this.face == null || this.b == null) {
            throw new RaceGateException("Gate has null values from DB.",
                    "This track is not supported in this version. Please rebuild the track.");
        }
        RaceMath.loadGate(this.dimension, this.a, this.face, this.b, checkSolid, this);
    }

    public void setBoundaries(BlockPos origin, BlockPos farthest, int[] rowMin, int[] rowMax,
                              V3 right, V3 up, V3 normal, ArrayList<BlockPos> path) {
        this.origin = origin;
        this.farthest = farthest;
        this.rowMin = rowMin;
        this.rowMax = rowMax;
        this.right = right;
        this.up = up;
        this.normal = normal;
        this.path = path;
    }

    /** Center of the gate's AABB in world coordinates (blocks span [n, n+1]). */
    public V3 getCenter() {
        return new V3(
                (origin.getX() + farthest.getX() + 1) / 2f,
                (origin.getY() + farthest.getY() + 1) / 2f,
                (origin.getZ() + farthest.getZ() + 1) / 2f);
    }

    public boolean containsBlock(BlockPos p) {
        return origin.getX() <= p.getX() && p.getX() <= farthest.getX()
                && origin.getY() <= p.getY() && p.getY() <= farthest.getY()
                && origin.getZ() <= p.getZ() && p.getZ() <= farthest.getZ();
    }
}
