package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class DroneState {

    public static boolean active = false;

    // ---- Camera ----
    /** How far the camera is tilted UP relative to the drone's body, in degrees. */
    public static final float CAMERA_TILT_DEG = 25f;
    private static final float CAMERA_TILT_RAD = (float) Math.toRadians(CAMERA_TILT_DEG);

    // ---- Physics tuning (1 block = 1 meter) ----
    public static final float GRAVITY = 9.8f;                 // m/s^2
    public static final float MAX_THRUST_ACCEL = 24.5f;       // m/s^2 at full throttle (2.5 g)
    public static final float HOVER_THROTTLE = GRAVITY / MAX_THRUST_ACCEL; // ~0.4
    public static final float THROTTLE_RATE = 0.6f;           // throttle change per second while W/S held
    public static final float DRAG = 0.5f;                    // 1/s, how quickly speed bleeds off

    // ---- State ----
    /** The drone BODY's orientation. Input rotates this in the body's local axes. */
    public static final Quaternionf orientation = new Quaternionf();
    public static Vec3 position = Vec3.ZERO;
    public static final Vector3f velocity = new Vector3f();
    /** 0..1 */
    public static float throttle;

    // The player's head rotation is pinned to these while in drone mode.
    // Any deviation from them is that frame's mouse movement.
    public static final float LOCKED_PITCH = 0f;
    public static float lockedYaw;

    private static float origYaw, origPitch;
    public static long lastNanos;

    /** Camera orientation = body orientation tilted up by the camera angle. */
    public static Quaternionf getCameraOrientation(Quaternionf dest) {
        return dest.set(orientation).rotateX(CAMERA_TILT_RAD);
    }

    public static void enter(LocalPlayer player) {
        active = true;

        origYaw = player.getYRot();
        origPitch = player.getXRot();
        lockedYaw = origYaw;

        // Camera starts exactly where the player was looking; the body is
        // pre-compensated for the camera tilt.
        orientation.rotationYXZ(
                        (float) Math.PI - (float) Math.toRadians(origYaw),
                        -(float) Math.toRadians(origPitch),
                        0f)
                .rotateX(-CAMERA_TILT_RAD);

        // The drone starts at the player's eyes, at rest, at hover throttle.
        position = player.getEyePosition();
        velocity.zero();
        throttle = HOVER_THROTTLE;

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

    /**
     * Advances the drone by dt seconds.
     * @param throttleInput +1 while W is held, -1 while S is held, else 0
     */
    public static Vec3 stepPhysics(float dt, float throttleInput) {
        // Throttle holds its value when no key is pressed.
        throttle = Math.max(0f, Math.min(1f, throttle + throttleInput * THROTTLE_RATE * dt));

        // Thrust acts along the BODY's up axis (not the camera's).
        Vector3f accel = new Vector3f(0f, 1f, 0f)
                .rotate(orientation)
                .mul(throttle * MAX_THRUST_ACCEL);
        accel.y -= GRAVITY;

        velocity.fma(dt, accel);                           // v += a * dt
        velocity.mul((float) Math.exp(-DRAG * dt));        // linear drag

        position = position.add(velocity.x * dt, velocity.y * dt, velocity.z * dt);

        return position;
    }
}