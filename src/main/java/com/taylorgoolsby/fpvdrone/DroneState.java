package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client-only drone state. Imports LocalPlayer, so it must never be loaded
 * on a dedicated server. Server-safe shared state lives in DroneRegistry.
 */
public class DroneState {

    public static boolean active = false;

    public static final float CAMERA_TILT_DEG = 25f;
    private static final float CAMERA_TILT_RAD =
            (float) Math.toRadians(CAMERA_TILT_DEG);

    public static final float GRAVITY = 9.8f;
    public static final float MAX_THRUST_ACCEL = 24.5f;
    public static final float HOVER_THROTTLE =
            GRAVITY / MAX_THRUST_ACCEL;
    public static final float THROTTLE_RATE = 0.6f;
    public static final float DRAG = 0.5f;

    public static final Quaternionf orientation = new Quaternionf();
    public static Vec3 position = Vec3.ZERO;
    public static final Vector3f velocity = new Vector3f();
    public static float throttle;

    public static final float LOCKED_PITCH = 0f;
    public static float lockedYaw;

    private static float origYaw;
    private static float origPitch;

    public static long lastNanos;

    public static Quaternionf getCameraOrientation(Quaternionf dest) {
        return dest.set(orientation).rotateX(CAMERA_TILT_RAD);
    }

    public static void enter(LocalPlayer player) {
        active = true;

        // Registry must be set BEFORE refreshDimensions(), which reads it.
        DroneRegistry.set(player.getUUID(), true);

        origYaw = player.getYRot();
        origPitch = player.getXRot();
        lockedYaw = origYaw;

        orientation.rotationYXZ(
                (float) Math.PI - (float) Math.toRadians(origYaw),
                -(float) Math.toRadians(origPitch),
                0f
        ).rotateX(-CAMERA_TILT_RAD);

        position = player.position();
        velocity.zero();
        throttle = HOVER_THROTTLE;

        player.refreshDimensions();
        pinPlayerHead(player);

        // Tell the server so its ServerPlayer uses the same small hitbox.
        DroneNetwork.sendToggle(true);

        lastNanos = System.nanoTime();
    }

    public static void exit(LocalPlayer player) {
        active = false;

        DroneRegistry.set(player.getUUID(), false);

        velocity.zero();

        player.refreshDimensions();
        DroneNetwork.sendToggle(false);

        player.setYRot(origYaw);
        player.setXRot(origPitch);
        player.yRotO = origYaw;
        player.xRotO = origPitch;
    }

    /** Clears drone state without needing a player. Call on disconnect. */
    public static void reset() {
        active = false;
        velocity.zero();
        DroneRegistry.clear();
    }

    public static void pinPlayerHead(LocalPlayer player) {
        player.setYRot(lockedYaw);
        player.setXRot(LOCKED_PITCH);
        player.yRotO = lockedYaw;
        player.xRotO = LOCKED_PITCH;
    }

    public static Vec3 stepPhysics(
            LocalPlayer player,
            float dt,
            float throttleInput
    ) {
        throttle = Math.max(
                0f,
                Math.min(1f, throttle + throttleInput * THROTTLE_RATE * dt)
        );

        Vector3f accel = new Vector3f(0f, 1f, 0f)
                .rotate(orientation)
                .mul(throttle * MAX_THRUST_ACCEL);

        accel.y -= GRAVITY;

        velocity.fma(dt, accel);
        velocity.mul((float) Math.exp(-DRAG * dt));

        Vec3 requestedMovement = new Vec3(
                velocity.x * dt,
                velocity.y * dt,
                velocity.z * dt
        );

        // Let Minecraft resolve collisions using the player's bounding box.
        Vec3 before = player.position();
        player.move(MoverType.SELF, requestedMovement);
        Vec3 actualMovement = player.position().subtract(before);

        // Remove velocity along axes blocked by collision.
        if (Math.abs(actualMovement.x - requestedMovement.x) > 1.0E-5) {
            velocity.x = 0f;
        }

        if (Math.abs(actualMovement.y - requestedMovement.y) > 1.0E-5) {
            velocity.y = 0f;
        }

        if (Math.abs(actualMovement.z - requestedMovement.z) > 1.0E-5) {
            velocity.z = 0f;
        }

        position = player.position();
        return position;
    }
}