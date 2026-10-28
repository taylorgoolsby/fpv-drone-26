package com.taylorgoolsby.fpvdrone;

import com.taylorgoolsby.fpvdrone.race.V3;

/**
 * Bouncing collisions, ported from the 1.16.5 mod's PhysicsCollision.
 *
 * When the drone hits something it does not simply stop. Its remaining
 * movement for the tick is reflected off the surface, and it leaves with a
 * fraction of its speed:
 *
 *   head-on hit:  keeps ELASTIC (20%), so 80% is lost
 *   grazing hit:  keeps GLANCING_KEEP (65%), so 35% is lost
 *
 * and it blends between the two with sin(angle), where angle is 0 for a
 * head-on hit and 90 degrees for a graze. If the speed after the bounce is
 * below MIN_BOUNCE_SPEED the drone just stops.
 *
 * Pure math, no Minecraft imports.
 */
public final class DroneCollision {
    /** Fraction of speed kept in a head-on collision. */
    public static final float ELASTIC = 0.2f;
    /** Fraction of speed kept in a perfectly grazing collision. */
    public static final float GLANCING_KEEP = 0.65f;
    /** Bounces slower than this (m/s) are not bounces: the drone stops. */
    public static final float MIN_BOUNCE_SPEED = 1f;

    /**
     * @param velocity     the velocity after the bounce, in m/s
     * @param displacement where the drone should end up this tick, relative to where it
     *                     started the tick. Equal to the clipped movement when it just stops.
     */
    public record Result(V3 velocity, V3 displacement) {}

    private DroneCollision() {}

    /**
     * @param displacement        the movement the drone wanted this tick (meters)
     * @param clipped             the movement the world allowed (meters)
     * @param preCollisionVelocity the velocity before the collision (m/s)
     */
    public static Result resolve(V3 displacement, V3 clipped, V3 preCollisionVelocity) {
        float desiredSpeed = preCollisionVelocity.length();

        // vClip is the movement that was removed by the collision.
        V3 vClip = clipped.sub(displacement);
        float angle = displacement.normalized().angleBetween(vClip.mul(-1).normalized());
        V3 cProj = clipped.project(vClip.normalized());

        if (Float.isNaN(cProj.x)) {
            // clipped and vClip are both zero vectors.
            return new Result(V3.ZERO, clipped);
        }

        V3 hClip = cProj.sub(clipped).normalized().mul(vClip.length() * (float) Math.tan(angle));
        V3 puncture = clipped.add(hClip);
        V3 bounceFinal = clipped.add(vClip);
        V3 bounceDirection = bounceFinal.sub(puncture).normalized();

        float glancing = (float) Math.sin(angle);
        float outSpeed = desiredSpeed * (glancing * GLANCING_KEEP + (1f - glancing) * ELASTIC);
        if (outSpeed < MIN_BOUNCE_SPEED) {
            return new Result(V3.ZERO, clipped);
        }
        return new Result(bounceDirection.mul(outSpeed), bounceFinal);
    }
}