package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;

public class DroneState {

    public static boolean active = false;

    /**
     * The camera's full 3D orientation. Rotating with rotateX/Y/Z on a
     * quaternion applies the rotation in the drone's OWN (local) axes,
     * which is what makes FPV flight feel right: pitch is always "nose
     * up/down" relative to the drone, even when it's rolled sideways.
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

    public static void enter(LocalPlayer player) {
        active = true;

        origYaw = player.getYRot();
        origPitch = player.getXRot();
        lockedYaw = origYaw;

        // Start the drone looking exactly where the player was looking.
        // Matches the camera's yaw/pitch/roll convention (YXZ order).
        orientation.identity().rotationYXZ(
                (float) Math.PI - (float) Math.toRadians(origYaw),
                -(float) Math.toRadians(origPitch),
                0f);

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