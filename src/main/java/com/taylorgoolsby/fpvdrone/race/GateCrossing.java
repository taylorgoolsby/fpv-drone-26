package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;

/**
 * Decides whether a movement from one position to another passed through a gate.
 * Pure math: no world access, no Minecraft state beyond BlockPos.
 *
 * The player's coordinates are those of the center of the bottom of the collision box.
 * A block's position is the lower north-west corner, i.e. the coordinates rounded down.
 */
public final class GateCrossing {
    private static final double FLT_EPSILON = 1.1920929E-7;

    private GateCrossing() {}

    /**
     * 1. Line-plane intersection to figure out if the movement reached the gate's plane and where.
     * 2. Check if the block crossed in is inside the gate:
     *    - between gate.origin and gate.farthest;
     *    - which "row" it is on;
     *    - whether its "column" is between rowMin and rowMax.
     *
     * @param prev         where the movement started (x, y, z)
     * @param cur          where it ended
     * @param intersection receives the point where the movement crossed the gate's plane
     */
    public static boolean passedThrough(RaceGate gate, String dimension, double[] prev, double[] cur, double[] intersection) {
        if (!dimension.equals(gate.dimension)) return false;

        double dx = cur[0] - prev[0];
        double dy = cur[1] - prev[1];
        double dz = cur[2] - prev[2];
        double travel2 = dx * dx + dy * dy + dz * dz;
        if (travel2 == 0) return false; // No movement.

        double len = Math.sqrt(travel2);
        double ux = dx / len, uy = dy / len, uz = dz / len;

        // The plane passes through the middle of the gate's layer of blocks.
        double nx = gate.normal.x, ny = gate.normal.y, nz = gate.normal.z;
        double px = gate.origin.getX() + Math.abs(nx) * 0.5;
        double py = gate.origin.getY() + Math.abs(ny) * 0.5;
        double pz = gate.origin.getZ() + Math.abs(nz) * 0.5;
        double constant = nx * px + ny * py + nz * pz;

        double denominator = nx * ux + ny * uy + nz * uz;
        if (denominator > -FLT_EPSILON && denominator < FLT_EPSILON) return false; // moving parallel to the plane
        double numerator = -((nx * prev[0] + ny * prev[1] + nz * prev[2]) - constant);
        double ratio = numerator / denominator; // distance along the (unit) travel direction
        if (ratio < FLT_EPSILON) return false;  // the plane is behind the start of the movement

        // Negative bias is needed for almost reaching the gate and for floating point errors.
        boolean gateHit = (travel2 - ratio * ratio) > -(0.001 * 0.001);
        if (!gateHit) return false;

        double hx = prev[0] + ux * ratio;
        double hy = prev[1] + uy * ratio;
        double hz = prev[2] + uz * ratio;

        // The block they were in when crossing:
        BlockPos crossingBlock = new BlockPos((int) Math.floor(hx), (int) Math.floor(hy), (int) Math.floor(hz));
        boolean pass = isGateBlock(dimension, crossingBlock, gate);
        intersection[0] = hx;
        intersection[1] = hy;
        intersection[2] = hz;
        return pass;
    }

    public static boolean isGateBlock(String dimension, BlockPos pos, RaceGate gate) {
        if (!gate.dimension.equals(dimension)) return false;

        // The crossing block must be inside the gate's bounding box.
        int minX = gate.origin.getX(), minY = gate.origin.getY(), minZ = gate.origin.getZ();
        int maxX = gate.farthest.getX(), maxY = gate.farthest.getY(), maxZ = gate.farthest.getZ();
        boolean insideX = minX == maxX ? minX == pos.getX() : minX <= pos.getX() && pos.getX() <= maxX;
        boolean insideY = minY == maxY ? minY == pos.getY() : minY <= pos.getY() && pos.getY() <= maxY;
        boolean insideZ = minZ == maxZ ? minZ == pos.getZ() : minZ <= pos.getZ() && pos.getZ() <= maxZ;
        if (!(insideX && insideY && insideZ)) return false;

        V3 originToCrossing = new V3(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
        int row = (int) Math.floor(originToCrossing.dot(gate.up));
        int column = (int) Math.floor(originToCrossing.dot(gate.right));
        if (row < 0 || row >= gate.rowMin.length) return false;

        int rowMin = gate.rowMin[row];
        int rowMax = gate.rowMax[row];
        // Passing is allowed through the solid edge blocks as well, to give leeway for:
        //   - a laggy connection
        //   - non-full size edge blocks, like stairs, gates, poles, etc.
        return rowMin <= column && column <= rowMax;
    }
}
