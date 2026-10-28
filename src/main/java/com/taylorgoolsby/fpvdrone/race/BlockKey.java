package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** A block position qualified by its dimension id. */
public final class BlockKey {
    public final String dimension;
    public final BlockPos pos;

    public BlockKey(String dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos;
    }

    @Override
    public int hashCode() { return Objects.hash(dimension, pos); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockKey k)) return false;
        return dimension.equals(k.dimension) && pos.equals(k.pos);
    }

    @Override
    public String toString() { return dimension + ", " + pos; }
}
