package com.taylorgoolsby.fpvdrone;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-safe set of players currently in drone mode. */
public final class DroneRegistry {
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

    private DroneRegistry() {}

    public static boolean isDrone(UUID id) {
        return ACTIVE.contains(id);
    }

    public static void set(UUID id, boolean on) {
        if (on) {
            ACTIVE.add(id);
        } else {
            ACTIVE.remove(id);
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }
}