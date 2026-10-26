package com.taylorgoolsby.fpvdrone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderAvatarEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Client-only. Draws a player in drone mode as a floating head: no body, arms,
 * legs, armor, held items, cape or wings, with the head centered in the
 * drone's hitbox instead of sitting at standing-player height.
 *
 * Two parts:
 *   - onPre() edits the render state before vanilla draws the player.
 *   - AvatarRendererMixin calls positionHead() to move the head to the center
 *     of the hitbox and turn it with the drone camera. The local player uses
 *     their own camera; other players' cameras arrive by packet and are kept
 *     in DroneOrientations.
 */
public final class DroneHeadRender {

    /**
     * Height of the head's center above the model's pivot, in model space
     * after the renderer's translate(0, -1.501, 0): the head's box is 8 px
     * tall, ending at the neck, so its center is 4 px (0.25) above it.
     */
    private static final float HEAD_CENTER_ABOVE_ORIGIN = 1.501f + 0.25f;

    /** AvatarRenderer.scale() scales the model by this. */
    private static final float MODEL_SCALE = 0.9375f;

    /**
     * Height of the drone hitbox. Matches PlayerDimensionsMixin. Used instead
     * of the render state's bounding box height, which on another client
     * stays at the standing height until that client refreshes the entity's
     * dimensions.
     */
    private static final float DRONE_BOX_HEIGHT = 0.3f;

    private DroneHeadRender() {}

    public static void register() {
        RenderAvatarEvent.Pre.BUS.addListener(DroneHeadRender::onPre);
    }

    /** The UUID of the player this state was extracted from, if they're in drone mode. */
    private static UUID droneUuid(AvatarRenderState state) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;

        Entity entity = level.getEntity(state.id);
        if (entity instanceof Player player
                && DroneRegistry.isDrone(player.getUUID())) {
            return player.getUUID();
        }
        return null;
    }

    /**
     * Another client only learns a player entered drone mode from a packet,
     * so nothing makes their entity pick up the small hitbox. Do it here: this
     * runs every frame for every drone player we draw.
     */
    private static void ensureDroneHitbox(AvatarRenderState state) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        Entity entity = level.getEntity(state.id);
        if (entity instanceof Player player
                && Math.abs(player.getBbHeight() - DRONE_BOX_HEIGHT) > 0.001f) {
            player.refreshDimensions();
        }
    }

    /** Whether the player this state was extracted from is in drone mode. */
    public static boolean isDroneAvatar(AvatarRenderState state) {
        return droneUuid(state) != null;
    }

    /** Whether we know where this player's drone camera is pointing. */
    private static boolean hasCameraOrientation(AvatarRenderState state, UUID uuid) {
        return isLocalDrone(state) || DroneOrientations.has(uuid);
    }

    /**
     * Where this player's drone camera points: our own camera for the local
     * player, the last one relayed by the server for anyone else.
     */
    private static boolean cameraOrientation(
            AvatarRenderState state, UUID uuid, Quaternionf dest
    ) {
        if (isLocalDrone(state)) {
            DroneState.getCameraOrientation(dest);
            return true;
        }
        return DroneOrientations.get(uuid, dest);
    }

    private static void onPre(RenderAvatarEvent.Pre event) {
        AvatarRenderState state = event.getState();
        UUID uuid = droneUuid(state);
        if (uuid == null) return;

        ensureDroneHitbox(state);
        if (DroneDebug.isEnabled()) {
            ClientLevel level = Minecraft.getInstance().level;
            Entity entity = level == null ? null : level.getEntity(state.id);
            DroneDebug.markRender(
                    uuid,
                    entity == null ? "?" : entity.getName().getString(),
                    isLocalDrone(state),
                    DroneOrientations.has(uuid),
                    state.boundingBoxHeight
            );
        }

        // PlayerModel hides the body, arms and legs (and with them the jacket,
        // sleeves and pants) for spectators, and AvatarRenderer skips every
        // render layer for them: armor, held items, cape, wings, parrots,
        // arrows. That leaves the head and its hat layer.
        state.isSpectator = true;

        // Upright, with no pose-specific rotation or offset: this is a head,
        // not a person crouching, swimming or gliding.
        state.pose = Pose.STANDING;
        state.isCrouching = false;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.isAutoSpinAttack = false;
        state.swimAmount = 0f;

        // The head is turned by positionHead() to match the drone camera,
        // including roll, which vanilla's yaw and pitch can't show. Zero the
        // vanilla angles so the two don't add up. If we haven't heard where a
        // remote player's camera points yet, leave vanilla's angles alone.
        if (hasCameraOrientation(state, uuid)) {
            state.bodyRot = 0f;
            state.yRot = 0f;
            state.xRot = 0f;
        }
    }

    /** Whether this state is the local player while flying the drone. */
    private static boolean isLocalDrone(AvatarRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        return DroneState.active
                && mc.player != null
                && state.id == mc.player.getId();
    }

    /**
     * Moves the model so the head's center lands at the center of the hitbox,
     * and turns the head to face where that player's drone camera does.
     * Called from AvatarRendererMixin, in the renderer's flipped and scaled
     * model space, just before it translates down to the model origin.
     */
    public static void positionHead(AvatarRenderState state, PoseStack poseStack) {
        poseStack.translate(0f, headShift(state), 0f);

        UUID uuid = droneUuid(state);
        if (uuid == null) return;

        Quaternionf camera = new Quaternionf();
        if (!cameraOrientation(state, uuid, camera)) return;

        // Vanilla has already applied R_y(180) and the (-1, -1, 1) flip.
        // Together they are a 180 degree turn about X. What is left to apply
        // so the head ends up in the camera's frame is:
        //   rotX(180) * camera * rotZ(180)
        // (the last term converts between the model's axes, +x = left and
        // +y = down, and the camera's, +x = right and +y = up).
        Quaternionf turn = new Quaternionf()
                .rotateX((float) Math.PI)
                .mul(camera)
                .rotateZ((float) Math.PI);
        Vector3f euler = turn.getEulerAnglesYXZ(new Vector3f());

        if (DroneDebug.isEnabled()) {
            DroneDebug.markApplied(uuid,
                    (float) Math.toDegrees(euler.y),
                    (float) Math.toDegrees(euler.x),
                    (float) Math.toDegrees(euler.z));
        }

        // Rotate about the head's center. In this space it is
        // HEAD_CENTER_ABOVE_ORIGIN above the origin, which is the negative y
        // direction because the space is flipped.
        poseStack.translate(0f, -HEAD_CENTER_ABOVE_ORIGIN, 0f);
        poseStack.rotate(Axis.YP, euler.y);
        poseStack.rotate(Axis.XP, euler.x);
        poseStack.rotate(Axis.ZP, euler.z);
        poseStack.translate(0f, HEAD_CENTER_ABOVE_ORIGIN, 0f);
    }

    /**
     * How far to translate, in the renderer's flipped and scaled model space,
     * so the head's center lands at the center of the hitbox.
     *
     * A standing model has its head center 1.751 * 0.9375 blocks above the
     * entity's origin (its feet). The hitbox center is half its height above
     * the same origin. The translation happens after the 0.9375 scale and the
     * (-1, -1, 1) flip, so it is divided by the scale and moves the model
     * down for a positive value.
     */
    private static float headShift(AvatarRenderState state) {
        float hitboxCenter = DRONE_BOX_HEIGHT * 0.5f;
        return HEAD_CENTER_ABOVE_ORIGIN - hitboxCenter / MODEL_SCALE;
    }
}