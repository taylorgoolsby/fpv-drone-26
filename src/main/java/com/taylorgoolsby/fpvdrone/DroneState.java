package com.taylorgoolsby.fpvdrone;

import com.taylorgoolsby.fpvdrone.race.V3;
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

    /** How far the camera is tilted up from the drone's body, in radians. */
    private static float cameraTiltRad() {
        return (float) Math.toRadians(
                DroneBuild.get(DroneBuild.Param.CAMERA_ANGLE));
    }

    // Thrust, drag, gravity and motor behaviour come from AdvancedPhysicsCore,
    // configured by DroneBuild (FPV Settings > Build).
    public static final float THROTTLE_RATE = 0.6f;

    private static final AdvancedPhysicsCore physics =
            new AdvancedPhysicsCore();

    public static final Quaternionf orientation = new Quaternionf();
    public static Vec3 position = Vec3.ZERO;
    public static final Vector3f velocity = new Vector3f();
    public static float throttle;

    // Stick positions shown by the HUD overlay (see DroneHud), each -1..1.
    // Written every frame by DroneClientEvents.
    public static float hudRoll;      // +1 = right
    public static float hudPitch;     // +1 = stick forward (nose down)
    public static float hudYaw;       // +1 = right
    public static float hudThrottle;  // -1 = zero throttle, +1 = full

    public static final float LOCKED_PITCH = 0f;
    public static float lockedYaw;

    private static float origYaw;
    private static float origPitch;

    public static long lastNanos;

    public static Quaternionf getCameraOrientation(Quaternionf dest) {
        return dest.set(orientation).rotateX(cameraTiltRad());
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
        ).rotateX(-cameraTiltRad());

        position = player.position();
        velocity.zero();
        throttle = physics.getHoverThrottle();
        physics.reset(throttle);
        hudRoll = hudPitch = hudYaw = 0f;
        hudThrottle = throttle * 2f - 1f;

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
        DroneOrientations.clear();
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

        // Motors, props, drag and gravity. Updates velocity in place.
        physics.step(dt, throttle, orientation, velocity);

        Vec3 requestedMovement = new Vec3(
                velocity.x * dt,
                velocity.y * dt,
                velocity.z * dt
        );
        V3 preCollisionVelocity = new V3(velocity.x, velocity.y, velocity.z);

        // Let Minecraft resolve collisions using the player's bounding box.
        Vec3 before = player.position();
        player.move(MoverType.SELF, requestedMovement);
        Vec3 clippedMovement = player.position().subtract(before);

        boolean collided =
                Math.abs(clippedMovement.x - requestedMovement.x) > 1.0E-5
                        || Math.abs(clippedMovement.y - requestedMovement.y) > 1.0E-5
                        || Math.abs(clippedMovement.z - requestedMovement.z) > 1.0E-5;

        if (collided) {
            // Bounce instead of stopping: see DroneCollision.
            DroneCollision.Result bounce = DroneCollision.resolve(
                    new V3((float) requestedMovement.x, (float) requestedMovement.y, (float) requestedMovement.z),
                    new V3((float) clippedMovement.x, (float) clippedMovement.y, (float) clippedMovement.z),
                    preCollisionVelocity
            );
            velocity.set(bounce.velocity().x, bounce.velocity().y, bounce.velocity().z);

            // The drone reached the surface; the rest of the tick's movement is
            // the reflected part. Moving with player.move keeps it from being
            // pushed into other blocks.
            Vec3 reflected = new Vec3(
                    bounce.displacement().x - clippedMovement.x,
                    bounce.displacement().y - clippedMovement.y,
                    bounce.displacement().z - clippedMovement.z
            );
            if (reflected.lengthSqr() > 1.0E-10) {
                player.move(MoverType.SELF, reflected);
            }
        }

        position = player.position();
        return position;
    }
}