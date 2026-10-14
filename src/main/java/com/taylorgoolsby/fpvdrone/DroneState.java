package com.taylorgoolsby.fpvdrone;

/**
 * Holds all of the drone's state in one place.
 *
 * Everything is static because there is only ever one local player on the
 * client, so there is only ever one drone. This is a simple approach that
 * works fine for a client-side-only feature. If you later want multiple
 * drones or server awareness, this would become a normal instance object.
 *
 * IMPORTANT: this class is only for the client. Never touch it from
 * server-side code, because the logical server has no concept of it.
 */
public class DroneState {

    /** True while the player is "flying the drone." Everything else checks this flag. */
    public static boolean active = false;

    /**
     * The camera's orientation in degrees.
     *   yaw   = rotation left/right (around the vertical axis)
     *   pitch = rotation up/down (positive looks DOWN in Minecraft)
     *   roll  = tilt of the camera around its forward axis (like banking a plane)
     *
     * A real FPV drone uses all three, and roll is what makes FPV feel
     * different from normal Minecraft, where roll is always 0.
     */
    public static float yaw, pitch, roll;

    /**
     * Turns drone mode on. We start the drone's camera at whatever angle
     * the player was already looking, so entering drone mode doesn't cause
     * a sudden visual jump.
     */
    public static void enter(float startYaw, float startPitch) {
        active = true;
        yaw = startYaw;
        pitch = startPitch;
        roll = 0f; // Always start level.
    }

    /** Turns drone mode off. The camera goes back to normal behavior automatically. */
    public static void exit() {
        active = false;
    }
}