package com.taylorgoolsby.fpvdrone.race;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelData;

/**
 * The world spawn point.
 *
 * NOTE: since Minecraft 1.21.9 the world spawn is stored as LevelData.RespawnData
 * (a dimension, a position and a rotation). This is the only class that reads it.
 * If a vanilla signature differs in 26.3, this is the only file to fix.
 */
public final class RaceSpawn {
    /** The level the world spawn is in, its block position and the yaw players face when they spawn. */
    public record Spawn(ServerLevel level, BlockPos pos, float yaw) {}

    private RaceSpawn() {}

    /** @return the world spawn, or null if its dimension is not loaded */
    public static Spawn get(MinecraftServer server) {
        LevelData.RespawnData data = server.overworld().getRespawnData();
        ServerLevel level = server.getLevel(data.dimension());
        if (level == null) return null;
        return new Spawn(level, data.pos(), data.yaw());
    }
}