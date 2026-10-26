package com.taylorgoolsby.fpvdrone;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostics for the multiplayer floating head, shown on screen (see
 * DroneController) and, rate-limited, in the log.
 *
 * Off by default. On the client, turn it on with FPV Settings > Other
 * Settings > Show Head Sync Debug. On a dedicated server, which has no
 * settings screen, start it with -Dfpvdrone.debug=true.
 *
 * Free of client-only imports: used on both sides.
 */
public final class DroneDebug {

    private static volatile boolean enabled = Boolean.getBoolean("fpvdrone.debug");

    private static final long LOG_INTERVAL_MILLIS = 1000L;
    private static final long STALE_NANOS = 2_000_000_000L;
    private static final long RATE_WINDOW_NANOS = 500_000_000L;

    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    /** What we know about one drone player's head, as seen from this client. */
    private static final class Head {
        String name = "?";
        boolean local;
        boolean hasOrientation;
        float stateBoxHeight;
        long renderNanos;

        long receivedCount;
        long receivedNanos;
        long rateCount;
        long rateNanos;
        float ratePerSecond;

        long appliedNanos;
        float yaw;
        float pitch;
        float roll;
    }

    private static final Map<UUID, Head> HEADS = new ConcurrentHashMap<>();

    private static volatile long hookNanos;
    private static volatile boolean hookDrone;

    private DroneDebug() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            HEADS.clear();
            hookNanos = 0L;
        }
    }

    // ------------------------------------------------------------------
    // Log
    // ------------------------------------------------------------------

    /** Logs at most once per second per key. */
    public static void log(String key, String message) {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        Long last = LAST_LOG.get(key);
        if (last != null && now - last < LOG_INTERVAL_MILLIS) return;

        LAST_LOG.put(key, now);
        FpvDroneMod.LOGGER.info("[fpvdrone-debug] {}", message);
    }

    // ------------------------------------------------------------------
    // Recording (all no-ops while disabled)
    // ------------------------------------------------------------------

    /** AvatarRendererMixin's hook ran for an avatar. */
    public static void markHook(boolean drone) {
        if (!enabled) return;
        hookNanos = System.nanoTime();
        hookDrone = drone;
    }

    /** A drone player was about to be drawn. */
    public static void markRender(
            UUID id, String name, boolean local, boolean hasOrientation, float stateBoxHeight
    ) {
        if (!enabled) return;
        Head head = HEADS.computeIfAbsent(id, key -> new Head());
        head.name = name;
        head.local = local;
        head.hasOrientation = hasOrientation;
        head.stateBoxHeight = stateBoxHeight;
        head.renderNanos = System.nanoTime();
    }

    /** An orientation packet arrived for a player. */
    public static void markReceived(UUID id) {
        if (!enabled) return;
        Head head = HEADS.computeIfAbsent(id, key -> new Head());
        head.receivedCount++;
        head.receivedNanos = System.nanoTime();
    }

    /** The rotation the head was drawn with, in degrees. */
    public static void markApplied(UUID id, float yaw, float pitch, float roll) {
        if (!enabled) return;
        Head head = HEADS.computeIfAbsent(id, key -> new Head());
        head.yaw = yaw;
        head.pitch = pitch;
        head.roll = roll;
        head.appliedNanos = System.nanoTime();
    }

    // ------------------------------------------------------------------
    // Screen text
    // ------------------------------------------------------------------

    /** One line summarising the head sync, for the action bar. */
    public static String screenText() {
        long now = System.nanoTime();

        StringBuilder sb = new StringBuilder("Head sync: mixin ");
        if (hookNanos == 0L) {
            sb.append("NOT RUNNING");
        } else {
            sb.append("ok ").append(millis(now - hookNanos)).append("ms");
        }

        boolean any = false;
        for (Head head : HEADS.values()) {
            long latest = Math.max(head.renderNanos, head.receivedNanos);
            if (now - latest > STALE_NANOS) continue;
            any = true;

            // Packets per second, over a short window so it stays readable.
            if (head.rateNanos == 0L) {
                head.rateNanos = now;
                head.rateCount = head.receivedCount;
            }
            long window = now - head.rateNanos;
            if (window > RATE_WINDOW_NANOS) {
                head.ratePerSecond =
                        (head.receivedCount - head.rateCount) * 1e9f / window;
                head.rateCount = head.receivedCount;
                head.rateNanos = now;
            }

            sb.append(" | ").append(head.name);
            if (head.local) sb.append(" (you)");

            sb.append(head.hasOrientation ? " orient yes" : " orient NO");
            sb.append(String.format(Locale.ROOT, " rx %.0f/s", head.ratePerSecond));

            if (head.receivedNanos != 0L) {
                sb.append(" last ").append(millis(now - head.receivedNanos)).append("ms");
            } else {
                sb.append(" never received");
            }

            if (now - head.renderNanos > STALE_NANOS) {
                sb.append(" NOT DRAWN");
            } else if (now - head.appliedNanos < STALE_NANOS) {
                sb.append(String.format(Locale.ROOT, " ypr %.0f %.0f %.0f",
                        head.yaw, head.pitch, head.roll));
            } else {
                sb.append(" rotation NOT APPLIED");
            }

            sb.append(String.format(Locale.ROOT, " box %.2f", head.stateBoxHeight));
        }

        if (!any) sb.append(" | no drone players seen");

        return sb.toString();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}