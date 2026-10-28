package com.taylorgoolsby.fpvdrone.race.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * A gate's axis aligned bounding box, in block coordinates (both corners inclusive).
 * This is all the client needs to draw and aim at a gate.
 */
public record GateBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(minX);
        buf.writeInt(minY);
        buf.writeInt(minZ);
        buf.writeInt(maxX);
        buf.writeInt(maxY);
        buf.writeInt(maxZ);
    }

    public static GateBox read(FriendlyByteBuf buf) {
        return new GateBox(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
    }

    public double centerX() { return (minX + maxX + 1) / 2.0; }

    public double centerY() { return (minY + maxY + 1) / 2.0; }

    public double centerZ() { return (minZ + maxZ + 1) / 2.0; }
}
