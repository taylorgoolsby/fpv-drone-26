package com.taylorgoolsby.fpvdrone;

import org.joml.Quaternionf;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where other players' drone cameras are pointing, as last reported by the
 * server. Used to turn their floating heads.
 *
 * Updates arrive about 20 times a second, so reads are smoothed: the value
 * returned eases toward the latest one instead of stepping.
 *
 * Free of client-only imports, like the packets that fill it.
 */
public final class DroneOrientations {

    /** Time constant of the smoothing, in seconds. */
    private static final float SMOOTHING_SECONDS = 0.04f;

    private static final class Entry {
        final Quaternionf target = new Quaternionf();
        final Quaternionf display = new Quaternionf();
        long lastNanos;
    }

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private DroneOrientations() {}

    /** Records the latest orientation. Ignores non-finite or zero input. */
    public static void set(UUID id, float x, float y, float z, float w) {
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (!Float.isFinite(lengthSquared) || lengthSquared < 1e-6f) return;

        float inverseLength = 1f / (float) Math.sqrt(lengthSquared);
        float nx = x * inverseLength;
        float ny = y * inverseLength;
        float nz = z * inverseLength;
        float nw = w * inverseLength;

        Entry entry = ENTRIES.computeIfAbsent(id, key -> {
            Entry created = new Entry();
            created.display.set(nx, ny, nz, nw);
            created.lastNanos = System.nanoTime();
            return created;
        });

        synchronized (entry) {
            entry.target.set(nx, ny, nz, nw);
        }
    }

    public static boolean has(UUID id) {
        return ENTRIES.containsKey(id);
    }

    /**
     * Gets the smoothed orientation for a player.
     *
     * @return false if nothing has been received for this player
     */
    public static boolean get(UUID id, Quaternionf dest) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return false;

        synchronized (entry) {
            long now = System.nanoTime();
            float dt = (now - entry.lastNanos) * 1e-9f;
            entry.lastNanos = now;

            float alpha = 1f - (float) Math.exp(-dt / SMOOTHING_SECONDS);
            entry.display.slerp(entry.target, alpha);
            dest.set(entry.display);
        }
        return true;
    }

    public static void remove(UUID id) {
        ENTRIES.remove(id);
    }

    public static void clear() {
        ENTRIES.clear();
    }
}