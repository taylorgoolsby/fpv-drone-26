package com.taylorgoolsby.fpvdrone.race.client;

import com.taylorgoolsby.fpvdrone.DroneState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client-only. Projects a world position onto the GUI, the way the game camera sees it.
 *
 * The camera is the player's eye. Its orientation is the drone camera's
 * quaternion while flying (the same one CameraMixin gives the vanilla camera,
 * so it includes roll and the camera tilt), or the normal view yaw and pitch.
 * The field of view is the Minecraft FOV option.
 */
final class RaceProjection {

    /** Result of a projection, in GUI-scaled pixels. */
    record Result(float x, float y, boolean onScreen, double distance) {}

    private RaceProjection() {}

    /**
     * @param margin how far from the screen edge an off-screen target is pinned, in GUI pixels
     */
    static Result project(double wx, double wy, double wz, float partialTick, int guiWidth, int guiHeight, float margin) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return null;

        Vec3 eye = player.getEyePosition(partialTick);

        Quaternionf rotation = new Quaternionf();
        if (DroneState.active) {
            DroneState.getCameraOrientation(rotation);
        } else {
            float yaw = player.getViewYRot(partialTick) * ((float) Math.PI / 180F);
            float pitch = player.getViewXRot(partialTick) * ((float) Math.PI / 180F);
            // Same convention as the vanilla camera and DroneState.enter.
            rotation.rotationYXZ((float) Math.PI - yaw, -pitch, 0f);
        }

        // World offset into view space: +x right, +y up, the camera looks down -z.
        Vector3f d = new Vector3f((float) (wx - eye.x), (float) (wy - eye.y), (float) (wz - eye.z));
        double distance = d.length();
        rotation.transformInverse(d);

        double fovRad = Math.toRadians(mc.options.fov().get());
        float tanHalf = (float) Math.tan(fovRad / 2.0);
        float aspect = guiWidth / (float) guiHeight;

        float cx = guiWidth / 2f;
        float cy = guiHeight / 2f;
        float depth = -d.z;

        float vx; // pixels from the screen center, +x right
        float vy; // pixels from the screen center, +y down
        boolean inFront = depth > 0.05f;
        if (inFront) {
            vx = (d.x / (depth * tanHalf * aspect)) * cx;
            vy = -(d.y / (depth * tanHalf)) * cy;
        } else {
            // Behind the camera. Point the marker the way you would have to turn.
            vx = d.x;
            vy = -d.y;
            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 1e-4f) {
                vx = 0;
                vy = cy; // directly behind: pin to the bottom
            } else {
                vx = vx / len * 10000f;
                vy = vy / len * 10000f;
            }
        }

        float halfW = cx - margin;
        float halfH = cy - margin;
        boolean inside = inFront && Math.abs(vx) <= halfW && Math.abs(vy) <= halfH;
        if (!inside) {
            // Scale the vector from the center so that it lands on the edge of the margin box.
            float sx = Math.abs(vx) > 1e-4f ? halfW / Math.abs(vx) : Float.MAX_VALUE;
            float sy = Math.abs(vy) > 1e-4f ? halfH / Math.abs(vy) : Float.MAX_VALUE;
            float s = Math.min(sx, sy);
            vx *= s;
            vy *= s;
        }

        return new Result(cx + vx, cy + vy, inside, distance);
    }
}
