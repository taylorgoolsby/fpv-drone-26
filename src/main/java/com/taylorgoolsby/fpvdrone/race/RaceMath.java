package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BiPredicate;

/**
 * Gate validation and geometry. This is a direct port of the 1.16.5
 * RaceMath. The algorithm is unchanged; only the math types differ
 * (see V3). Free of client-only imports and of world access: solidity is
 * supplied by the caller through a BiPredicate.
 *
 * Requirements a gate must satisfy:
 *  - Axis aligned (planar, exactly one fixed axis).
 *  - Closed (the edge walk returns to its start).
 *  - Convex.
 *  - Every interior block is non-solid.
 *  - Edge blocks touch the interior air face to face (corners do not count).
 *  - The edge has at most MAX_PATH_LENGTH blocks.
 */
public class RaceMath {
    public static final int MAX_PATH_LENGTH = 64;

    public static int countFixedAxes2(BlockPos a, BlockPos b) {
        // Diagonal lines and planes are not considered.
        // If only 1 axis is fixed, then the blocks are planar.
        // If more than 1 axes are fixed, then the blocks are linear.
        int fixedAxes = 0;
        if (a.getX() == b.getX()) fixedAxes++;
        if (a.getZ() == b.getZ()) fixedAxes++;
        if (a.getY() == b.getY()) fixedAxes++;
        return fixedAxes;
    }

    public static int countFixedAxes(BlockPos a, BlockPos b, BlockPos c) {
        int fixedAxes = 0;
        if (a.getX() == b.getX() && a.getX() == c.getX()) fixedAxes++;
        if (a.getZ() == b.getZ() && a.getZ() == c.getZ()) fixedAxes++;
        if (a.getY() == b.getY() && a.getY() == c.getY()) fixedAxes++;
        return fixedAxes;
    }

    /*
     * fixedDimension == 0 means the x axis does not change
     * fixedDimension == 1 means the y axis does not change
     * fixedDimension == 2 means the z axis does not change
     */
    public static int getPlanarFixedDim(BlockPos a, BlockPos b, BlockPos c) throws RaceGateException {
        int nFixedDims = countFixedAxes(a, b, c);
        if (nFixedDims != 1) {
            throw new RaceGateException("Exactly 1 dimension should be fixed in order to be planar non-linear.");
        }
        if (a.getX() == b.getX() && a.getX() == c.getX()) return 0;
        if (a.getZ() == b.getZ() && a.getZ() == c.getZ()) return 2;
        if (a.getY() == b.getY() && a.getY() == c.getY()) return 1;
        throw new RaceGateException("Unable to find fixed dim.");
    }

    /*
     * Returns:
     *   result[0] := right
     *   result[1] := up
     *   result[2] := normal = right x up
     */
    public static V3[] getPlanarDirections(int fixedDimension) {
        V3 right = V3.ZERO;
        V3 up = V3.ZERO;
        if (fixedDimension == 0) {
            right = new V3(0, 0, 1);
            up = new V3(0, 1, 0);
        } else if (fixedDimension == 1) {
            right = new V3(1, 0, 0);
            up = new V3(0, 0, 1);
        } else if (fixedDimension == 2) {
            right = new V3(1, 0, 0);
            up = new V3(0, 1, 0);
        }
        V3 normal = right.cross(up);
        return new V3[]{right, up, normal};
    }

    /*
     * Overview of the algorithm:
     *   1. Reduce the problem to 2D since the gate is axis aligned.
     *   2. Find the air block touching face to face with block A on the interior of the gate.
     *   3. Starting with block A and its air block, use stepGateBlock and rotateAirBlock
     *      to traverse the gate's edge.
     *   4. If the walk dead-ends, the gate is not closed.
     *   5. If the iteration limit is reached, the gate is too big.
     *   6. Make sure B was visited.
     *   7. Compute the AABB (used for faster gate-passing detection).
     *   8. Build the interior blocks, along with min and max column of each row.
     *      Non-contiguous edge blocks mean the gate is not convex.
     *   9. Every interior block must be air, and the initial air block must be one of them.
     */
    public static RaceGate loadGate(String dimension, BlockPos a, Direction face, BlockPos b,
                                    BiPredicate<String, BlockPos> checkSolid, RaceGate local) throws RaceGateException {
        // The air block touching the face of A:
        BlockPos air = new BlockPos(a.getX() + face.getStepX(), a.getY() + face.getStepY(), a.getZ() + face.getStepZ());

        // Verify solidity
        if (!checkSolid.test(dimension, a) || !checkSolid.test(dimension, b) || checkSolid.test(dimension, air)) {
            throw new RaceGateException("Starting blocks do not have the correct solidity.");
        }

        int nFixedDims = countFixedAxes(a, b, air);
        if (nFixedDims != 1) {
            throw new RaceGateException("Unable to determine axis-aligned dimension.");
        }
        int fixedDim = getPlanarFixedDim(a, b, air);

        // Verify touching face to face:
        int distB = manhattan(a, b);
        boolean bTouching = distB == 1 || (distB == 2 && countFixedAxes2(a, b) == 1);
        boolean airTouching = manhattan(a, air) == 1;
        if (!bTouching || !airTouching) {
            throw new RaceGateException("Starting blocks are not touching correctly.");
        }

        boolean bVisited = false;
        BlockPos currentSolid = a;
        // rotate air before doing anything in order to be in a handled case.
        BlockPos currentAir = rotateAirBlock(fixedDim, dimension, currentSolid, air, checkSolid);
        ArrayList<BlockPos> path = new ArrayList<>(MAX_PATH_LENGTH);
        path.add(a);
        for (int length = 1; length < MAX_PATH_LENGTH + 1; length++) {
            BlockPos[] results = stepGateBlock(fixedDim, dimension, currentSolid, currentAir, checkSolid);

            if (path.size() >= 2 && results[0].equals(path.get(path.size() - 2))) {
                // Dead end reached. Gate path is not closed.
                throw new RaceGateException("Dead end. Gate not closed.");
            }

            currentSolid = results[0];
            currentAir = results[1];
            currentAir = rotateAirBlock(fixedDim, dimension, currentSolid, currentAir, checkSolid);

            if (currentSolid.equals(b)) {
                bVisited = true;
            }

            if (currentSolid.equals(a)) {
                // Break out when the beginning is reached again.
                break;
            }

            path.add(currentSolid); // This must be called in order to avoid an infinite loop.
        }
        if (path.size() > MAX_PATH_LENGTH) {
            throw new RaceGateException("Found path is greater than the allowed maximum.", "Gate is too big.");
        }
        if (path.size() == MAX_PATH_LENGTH) {
            if (!currentSolid.equals(a)) {
                throw new RaceGateException("Max iteration reached without wrapping back to starting point.", "Gate is too big.");
            }
        }
        if (!bVisited) {
            throw new RaceGateException("B was not visited.");
        }

        BlockPos[] aabb = getAABB(path);
        BlockPos origin = aabb[0];
        BlockPos farthest = aabb[1];
        GetInteriorResult interiorResult = getInteriorBlocks(fixedDim, aabb, path);
        ArrayList<BlockPos> interior = interiorResult.blocks;
        int[] rowMin = interiorResult.rowMin;
        int[] rowMax = interiorResult.rowMax;

        // Make sure every interior block is non-solid:
        boolean foundAir = false;
        for (BlockPos interiorBlock : interior) {
            if (checkSolid.test(dimension, interiorBlock)) {
                throw new RaceGateException("Found a solid interior block.", "Gate interior should not have solid blocks.");
            }
            if (air.equals(interiorBlock)) {
                foundAir = true;
            }
        }

        // The initial air block should have been found too.
        if (!foundAir) {
            throw new RaceGateException("Air was not visited.", "You must click on the gate's interior face.");
        }

        V3[] planar = getPlanarDirections(fixedDim);

        RaceGate result = local == null ? new RaceGate(dimension, a, face, b) : local;
        result.setBoundaries(origin, farthest, rowMin, rowMax, planar[0], planar[1], planar[2], path);
        return result;
    }

    /*
     * Returns:
     *   result[0] = origin (minimum coordinates)
     *   result[1] = farthest (maximum coordinates)
     */
    public static BlockPos[] getAABB(ArrayList<BlockPos> path) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos e : path) {
            if (e.getX() < minX) minX = e.getX();
            if (e.getY() < minY) minY = e.getY();
            if (e.getZ() < minZ) minZ = e.getZ();
            if (e.getX() > maxX) maxX = e.getX();
            if (e.getY() > maxY) maxY = e.getY();
            if (e.getZ() > maxZ) maxZ = e.getZ();
        }

        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    public static GetInteriorResult getInteriorBlocks(int fixedDimension, BlockPos[] maxAABB, ArrayList<BlockPos> path) throws RaceGateException {
        V3[] planar = getPlanarDirections(fixedDimension);
        V3 right = planar[0];
        V3 up = planar[1];

        V3 origin = new V3(maxAABB[0].getX(), maxAABB[0].getY(), maxAABB[0].getZ());
        V3 maxCorner = new V3(maxAABB[1].getX(), maxAABB[1].getY(), maxAABB[1].getZ());
        V3 span = maxCorner.sub(origin);
        // up and right are unit axes, so the projection length is |dot|.
        int maxRowIndex = Math.round(Math.abs(span.dot(up)));
        int nRows = maxRowIndex + 1;
        int maxColIndex = Math.round(Math.abs(span.dot(right)));
        int nCols = maxColIndex + 1;

        // Counting from the bottom of the gate, rowIndex is the row of the edge block.
        // colIndex is the count of blocks from the left edge of the gate.
        ArrayList<HashMap<Integer, BlockPos>> rows = new ArrayList<>();
        ArrayList<HashMap<Integer, BlockPos>> cols = new ArrayList<>();
        for (int i = 0; i < nRows; i++) rows.add(new HashMap<>());
        for (int i = 0; i < nCols; i++) cols.add(new HashMap<>());

        // maps a rowIndex to a colIndex.
        int[] rowMin = new int[nRows];
        int[] rowMax = new int[nRows];
        for (int i = 0; i < nRows; i++) {
            rowMin[i] = Integer.MAX_VALUE;
            rowMax[i] = Integer.MIN_VALUE;
        }

        // maps a colIndex to a rowIndex.
        int[] colMin = new int[nCols];
        int[] colMax = new int[nCols];
        for (int i = 0; i < nCols; i++) {
            colMin[i] = Integer.MAX_VALUE;
            colMax[i] = Integer.MIN_VALUE;
        }

        // Loop through the edges once to find the max and min column of each row,
        // and the min and max row of each column.
        for (BlockPos edgeBlock : path) {
            V3 local = new V3(edgeBlock.getX(), edgeBlock.getY(), edgeBlock.getZ()).sub(origin);
            int rowIndex = Math.round(Math.abs(local.dot(up)));
            int colIndex = Math.round(Math.abs(local.dot(right)));

            if (colIndex < rowMin[rowIndex]) rowMin[rowIndex] = colIndex;
            if (colIndex > rowMax[rowIndex]) rowMax[rowIndex] = colIndex;
            if (rowIndex < colMin[colIndex]) colMin[colIndex] = rowIndex;
            if (rowIndex > colMax[colIndex]) colMax[colIndex] = rowIndex;

            rows.get(rowIndex).put(colIndex, edgeBlock);
            cols.get(colIndex).put(rowIndex, edgeBlock);
        }

        // The returned rowMin and rowMax are the outer-most mins and maxes,
        // which means there may be solid interior blocks.
        int[] outerRowMin = rowMin.clone();
        int[] outerRowMax = rowMax.clone();

        int count;
        BlockPos next;

        // Remove blocks which are contiguous along a row:
        for (int rowIndex = 0; rowIndex < nRows; rowIndex++) {
            // Shrink rowMin:
            count = 0;
            do {
                int colIndex = rowMin[rowIndex];
                next = rows.get(rowIndex).get(colIndex + 1);
                rows.get(rowIndex).remove(colIndex); // Check off the list (every block must be visited)
                if (next != null) {
                    rowMin[rowIndex] = colIndex + 1;
                }
                count++;
            } while (next != null && count < MAX_PATH_LENGTH);

            // Shrink rowMax:
            count = 0;
            do {
                int colIndex = rowMax[rowIndex];
                next = rows.get(rowIndex).get(colIndex - 1);
                rows.get(rowIndex).remove(colIndex);
                if (next != null) {
                    rowMax[rowIndex] = colIndex - 1;
                }
                count++;
            } while (next != null && count < MAX_PATH_LENGTH);
        }

        // Remove blocks which are contiguous along a column (endcaps not checked):
        for (int colIndex = 0; colIndex < nCols; colIndex++) {
            // Shrink colMin:
            count = 0;
            do {
                int rowIndex = colMin[colIndex];
                next = cols.get(colIndex).get(rowIndex + 1);
                cols.get(colIndex).remove(rowIndex);
                if (next != null) {
                    colMin[colIndex] = rowIndex + 1;
                }
                count++;
            } while (next != null && count < MAX_PATH_LENGTH);

            // Shrink colMax:
            count = 0;
            do {
                int rowIndex = colMax[colIndex];
                next = cols.get(colIndex).get(rowIndex - 1);
                cols.get(colIndex).remove(rowIndex);
                if (next != null) {
                    colMax[colIndex] = rowIndex - 1;
                }
                count++;
            } while (next != null && count < MAX_PATH_LENGTH);
        }

        // Verify that all the blocks have been checked off the list
        // (otherwise there are non-contiguous edge blocks, i.e. concave):
        for (int rowIndex = 0; rowIndex < nRows; rowIndex++) {
            if (rows.get(rowIndex).size() != 0) {
                throw new RaceGateException("Gate is not convex.", "Gate is not convex.");
            }
        }
        for (int colIndex = 0; colIndex < nCols; colIndex++) {
            if (cols.get(colIndex).size() != 0) {
                throw new RaceGateException("Gate is not convex.", "Gate is not convex.");
            }
        }

        // Loop through each row's columns, exclusive of rowMin and rowMax
        // because the endpoints are solid.
        ArrayList<BlockPos> blocks = new ArrayList<>();
        for (int row = 1; row < nRows - 1; row++) {
            int currentRowMin = rowMin[row];
            int currentRowMax = rowMax[row];
            if (currentRowMin == Integer.MAX_VALUE || currentRowMax == Integer.MIN_VALUE) {
                // Defensive: a row without edge blocks cannot belong to a closed gate.
                throw new RaceGateException("Row without edge blocks.", "Gate is not closed.");
            }
            for (int col = currentRowMin + 1; col <= currentRowMax - 1; col++) {
                V3 pos = origin.add(right.mul(col)).add(up.mul(row));
                blocks.add(new BlockPos(Math.round(pos.x), Math.round(pos.y), Math.round(pos.z)));
            }
        }
        GetInteriorResult result = new GetInteriorResult();
        result.blocks = blocks;
        result.rowMin = outerRowMin;
        result.rowMax = outerRowMax;
        return result;
    }

    /*
     * When looking down the normal, this gets the next solid block in the CCW direction.
     *
     * There are only 2 cases allowed:
     *
     *   1:        2:
     *     aa        as
     *     ss        s
     *
     * (Case 3 is case 1 rotated 90 degrees; rotateAirBlock turns it into case 1.)
     */
    private static BlockPos[] stepGateBlock(int fixedDimension, String worldDimension, BlockPos currentSolid,
                                            BlockPos currentAir, BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
        V3 normal = getPlanarDirections(fixedDimension)[2];

        V3 solidPos = new V3(currentSolid.getX(), currentSolid.getY(), currentSolid.getZ());

        // Figure out the CCW direction.
        V3 solidToAir = new V3(currentAir.getX() - currentSolid.getX(),
                currentAir.getY() - currentSolid.getY(),
                currentAir.getZ() - currentSolid.getZ());
        V3 nextDir = solidToAir.cross(normal).normalized();

        // Check if we are in case 1 (straight) or case 2 (diagonal):
        BlockPos diagonalPos = toBlockPos(solidPos.add(nextDir).add(solidToAir));
        boolean isDiagonalSolid = checkSolid.test(worldDimension, diagonalPos);
        BlockPos straightPos = toBlockPos(solidPos.add(nextDir));
        boolean isStraightSolid = checkSolid.test(worldDimension, straightPos);
        if (isDiagonalSolid) {
            return new BlockPos[]{diagonalPos, currentAir};
        } else if (isStraightSolid) {
            // In this case, the currentAir also changes by sliding over 1 block.
            return new BlockPos[]{straightPos, diagonalPos};
        } else {
            throw new RaceGateException("Unable to determine if we are in case 1 or 2.");
        }
    }

    /*
     * Rotates the air block around the solid block until it cannot be rotated
     * anymore because there is a solid block in the way.
     *
     * stepGateBlock changes the air block in the straight case, so the only
     * starting state rotateAirBlock has to handle is:
     *
     *   a
     *   s
     */
    private static BlockPos rotateAirBlock(int fixedDimension, String worldDimension, BlockPos currentSolid,
                                           BlockPos currentAir, BiPredicate<String, BlockPos> checkSolid) throws RaceGateException {
        V3 normal = getPlanarDirections(fixedDimension)[2];

        V3 solidPos = new V3(currentSolid.getX(), currentSolid.getY(), currentSolid.getZ());
        BlockPos testAir = new BlockPos(currentAir.getX(), currentAir.getY(), currentAir.getZ());

        // Only 3 rotations can be done before going back to the beginning.
        for (int i = 0; i < 3; i++) {
            V3 solidToAir = new V3(testAir.getX() - currentSolid.getX(),
                    testAir.getY() - currentSolid.getY(),
                    testAir.getZ() - currentSolid.getZ());
            V3 nextDir = solidToAir.cross(normal).normalized();

            BlockPos diagonalPos = toBlockPos(solidPos.add(nextDir).add(solidToAir));
            boolean isDiagonalSolid = checkSolid.test(worldDimension, diagonalPos);
            BlockPos straightPos = toBlockPos(solidPos.add(nextDir));
            boolean isStraightSolid = checkSolid.test(worldDimension, straightPos);

            if (isDiagonalSolid || isStraightSolid) {
                // We can't rotate anymore so return the current testAir.
                return testAir;
            } else {
                testAir = straightPos;
            }
        }

        throw new RaceGateException("Unable to find neighboring solid block.", "A single block cannot be a gate.");
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    /** Floors each component, like the old BlockPos(double, double, double) constructor. */
    private static BlockPos toBlockPos(V3 pos) {
        return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
}
