package com.taylorgoolsby.fpvdrone;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;

public class DroneClientEvents {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("fpvdrone", "main"));

    public static final KeyMapping TOGGLE_DRONE = new KeyMapping(
            "key.fpvdrone.toggle",
            InputConstants.Type.KEYBOARD,
            12, // SDL scancode for I
            CATEGORY
    );

    // Tuning values
    private static final float YAW_RATE_DEG_PER_SEC = 180f;
    private static final float ROLL_PER_MOUSE_DEG = 1.0f;
    private static final float PITCH_PER_MOUSE_DEG = 1.0f;

    public static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(DroneClientEvents::onRegisterKeys);
        TickEvent.ClientTickEvent.Post.BUS.addListener(DroneClientEvents::onClientTick);
        ViewportEvent.ComputeCameraAngles.BUS.addListener(DroneClientEvents::onCameraAngles);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_DRONE);
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        while (TOGGLE_DRONE.consumeClick()) {
            if (DroneState.active) {
                DroneState.exit(player);
            } else {
                DroneState.enter(player);
            }
        }
    }

    /**
     * Runs every frame. Order of work:
     *   1. Measure mouse movement (how far vanilla turned the player's head).
     *   2. Snap the head back so it never drifts.
     *   3. Apply mouse + key input to the quaternion, in the drone's local axes.
     *   4. Convert the quaternion to yaw/pitch/roll for the camera.
     */
    private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!DroneState.active) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // 1. Mouse deltas, in degrees (how far vanilla turned the head).
        float mouseX = player.getYRot() - DroneState.lockedYaw;
        float mouseY = player.getXRot() - DroneState.LOCKED_PITCH;

        // 2. Undo vanilla's head turn.
        DroneState.pinPlayerHead(player);

        // Frame time for rate-based input.
        long now = System.nanoTime();
        float dt = (now - DroneState.lastNanos) / 1_000_000_000f;
        DroneState.lastNanos = now;

        // 3. Apply input in the drone's local axes.
        float yawInput = (mc.options.keyRight.isDown() ? 1f : 0f)
                - (mc.options.keyLeft.isDown() ? 1f : 0f);

        DroneState.orientation
                .rotateX(-(float) Math.toRadians(mouseY * PITCH_PER_MOUSE_DEG))
                .rotateZ(-(float) Math.toRadians(mouseX * ROLL_PER_MOUSE_DEG))
                .rotateY(-(float) Math.toRadians(yawInput * YAW_RATE_DEG_PER_SEC * dt))
                .normalize();

        // No event.setYaw/Pitch/Roll: CameraMixin applies the quaternion directly.
    }
}