package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;

public class GetInteriorResult {
    public ArrayList<BlockPos> blocks;
    // rowMin / rowMax are column indices (from the gate's left side) of the
    // outermost solid edge blocks of each row. Anything strictly between them
    // is interior.
    public int[] rowMin;
    public int[] rowMax;
}
