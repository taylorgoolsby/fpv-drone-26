package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;

public class DroneState {

    public static boolean active = false;

    /** How far the camera is tilted UP relative to the drone's body, in degrees. */
    public static final float CAMERA_TILT_DEG = 25f;
    private static final float CAMERA_TILT_RAD = (float) Math.toRadians(CAMERA_TILT_DEG);

    /**
     * The drone BODY's orientation. All flight input rotates this, in the
     * body's own local axes. (Later, thrust will act along this body's up axis.)
     */
    public static final Quaternionf orientation = new Quaternionf();

    // The player's head rotation is pinned to these while in drone mode.
    // Any deviation from them is that frame's mouse movement.
    public static final float LOCKED_PITCH = 0f;
    public static float lockedYaw;

    // Saved so we can restore the player's view when leaving drone mode.
    private static float origYaw, origPitch;

    // For frame-time-based (rate) input.
    public static long lastNanos;

    /**
     * Writes the CAMERA's orientation into dest and returns it:
     * the body orientation, then tilted up by CAMERA_TILT_DEG around the
     * body's own X (pitch) axis. rotateX post-multiplies, so the tilt is
     * applied in the body's local frame. Positive X rotation tilts up.
     */
    public static Quaternionf getCameraOrientation(Quaternionf dest) {
        return dest.set(orientation).rotateX(CAMERA_TILT_RAD);
    }

    public static void enter(LocalPlayer player) {
        active = true;

        origYaw = player.getYRot();
        origPitch = player.getXRot();
        lockedYaw = origYaw;

        // We want the CAMERA to start exactly where the player was looking,
        // so the body starts as that view with the camera tilt removed
        // (the body begins pitched down by the tilt angle).
        orientation.rotationYXZ(
                        (float) Math.PI - (float) Math.toRadians(origYaw),
                        -(float) Math.toRadians(origPitch),
                        0f)
                .rotateX(-CAMERA_TILT_RAD);

        pinPlayerHead(player);
        lastNanos = System.nanoTime();
    }

    public static void exit(LocalPlayer player) {
        active = false;
        player.setYRot(origYaw);
        player.setXRot(origPitch);
        player.yRotO = origYaw;
        player.xRotO = origPitch;
    }

    /** Snap the player's head back to the locked angles. */
    public static void pinPlayerHead(LocalPlayer player) {
        player.setYRot(lockedYaw);
        player.setXRot(LOCKED_PITCH);
        player.yRotO = lockedYaw;
        player.xRotO = LOCKED_PITCH;
    }
}