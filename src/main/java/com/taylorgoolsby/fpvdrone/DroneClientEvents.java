package com.taylorgoolsby.fpvdrone;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;

public class DroneClientEvents {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("fpvdrone", "main")
            );

    public static final KeyMapping TOGGLE_DRONE = new KeyMapping(
            "key.fpvdrone.toggle",
            InputConstants.Type.KEYBOARD,
            12,
            CATEGORY
    );

    private static final float YAW_RATE_DEG_PER_SEC = 180f;
    private static final float ROLL_PER_MOUSE_DEG = 1.0f;
    private static final float PITCH_PER_MOUSE_DEG = 1.0f;

    // HUD only: mouse movement (degrees per frame) that counts as full stick,
    // and how quickly the displayed mouse "stick" eases toward its target.
    private static final float HUD_MOUSE_FULL_DEG = 6f;
    private static final float HUD_MOUSE_SMOOTHING = 0.35f;
    private static float hudMouseRoll;
    private static float hudMousePitch;

    public static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(
                DroneClientEvents::onRegisterKeys
        );

        TickEvent.ClientTickEvent.Pre.BUS.addListener(
                DroneClientEvents::onClientTickPre
        );

        TickEvent.ClientTickEvent.Post.BUS.addListener(
                DroneClientEvents::onClientTick
        );

        ViewportEvent.ComputeCameraAngles.BUS.addListener(
                DroneClientEvents::onCameraAngles
        );

        DroneHud.register();
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_DRONE);
    }

    private static void onClientTickPre(
            TickEvent.ClientTickEvent.Pre event
    ) {
        if (!DroneState.active) return;

        Minecraft mc = Minecraft.getInstance();

        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    private static void onClientTick(
            TickEvent.ClientTickEvent.Post event
    ) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // Detect / hot-plug controllers even when not in drone mode (and
        // even with no world loaded) so the controller is found before
        // takeoff and the settings screens work from the title screen.
        DroneController.tick(mc);

        if (player == null) return;

        // Death cleanup: leave drone mode so the server's registry flag is
        // cleared too, and the respawned player doesn't keep the small hitbox.
        if (DroneState.active && player.isDeadOrDying()) {
            DroneState.exit(player);
            // Discard any toggle presses made while dead.
            while (TOGGLE_DRONE.consumeClick()) { }
            return;
        }

        while (TOGGLE_DRONE.consumeClick()) {
            if (DroneState.active) {
                DroneState.exit(player);
            } else if (!player.isDeadOrDying()) {
                DroneState.enter(player);
            }

            player.refreshDimensions();
        }
    }

    private static boolean physicallyDown(KeyMapping key) {
        return !key.isUnbound()
                && InputConstants.isKeyDown(key.getKey().getValue());
    }

    private static void onCameraAngles(
            ViewportEvent.ComputeCameraAngles event
    ) {
        if (!DroneState.active) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) return;

        long now = System.nanoTime();

        float dt = Math.min(
                (now - DroneState.lastNanos) / 1_000_000_000f,
                0.1f
        );

        DroneState.lastNanos = now;

        if (mc.gui.screen() != null) return;

        float mouseX = player.getYRot() - DroneState.lockedYaw;
        float mouseY = player.getXRot() - DroneState.LOCKED_PITCH;

        DroneState.pinPlayerHead(player);

        float yawInput =
                (physicallyDown(mc.options.keyRight) ? 1f : 0f)
                        - (physicallyDown(mc.options.keyLeft) ? 1f : 0f);

        float throttleInput =
                (physicallyDown(mc.options.keyUp) ? 1f : 0f)
                        - (physicallyDown(mc.options.keyDown) ? 1f : 0f);

        // Mouse is relative, so show its per-frame movement as a smoothed stick.
        hudMouseRoll += (clamp1(mouseX / HUD_MOUSE_FULL_DEG) - hudMouseRoll)
                * HUD_MOUSE_SMOOTHING;
        hudMousePitch += (clamp1(mouseY / HUD_MOUSE_FULL_DEG) - hudMousePitch)
                * HUD_MOUSE_SMOOTHING;

        float hudYaw = yawInput;
        float hudRoll = hudMouseRoll;
        float hudPitch = hudMousePitch;

        // Without a controller the throttle is shown as its actual level
        // (0..1 -> stick -1..1). With one, it's the raw stick position.
        float hudThrottle = DroneState.throttle * 2f - 1f;

        float pitchDeg = mouseY * PITCH_PER_MOUSE_DEG;
        float rollDeg = mouseX * ROLL_PER_MOUSE_DEG;
        float yawDeg = yawInput * YAW_RATE_DEG_PER_SEC * dt;

        if (DroneController.poll()) {
            // The virtual joystick is driven by WASD itself, so don't also
            // apply the keyboard yaw/throttle (it would double up).
            if (DroneController.isVirtual()) {
                yawDeg = 0f;
                throttleInput = 0f;
                hudYaw = 0f;
            }

            // The HUD shows the raw sticks, before deadzone, expo and rates.
            hudYaw += DroneController.rawYaw;
            hudRoll += DroneController.rawRoll;
            hudPitch += DroneController.rawPitch;
            hudThrottle = DroneController.rawThrottle;

            pitchDeg += DroneController.pitchDegPerSec * dt;
            rollDeg += DroneController.rollDegPerSec * dt;
            yawDeg += DroneController.yawDegPerSec * dt;

            // The throttle stick position IS the throttle (no integration).
            DroneState.throttle = DroneController.throttle;
            throttleInput = 0f;
        }

        DroneState.orientation
                .rotateX(-(float) Math.toRadians(pitchDeg))
                .rotateZ(-(float) Math.toRadians(rollDeg))
                .rotateY(-(float) Math.toRadians(yawDeg))
                .normalize();

        // Position is updated by player.move() inside stepPhysics().
        Vec3 position = DroneState.stepPhysics(
                player,
                dt,
                throttleInput
        );

        DroneState.position = position;

        DroneState.hudYaw = clamp1(hudYaw);
        DroneState.hudRoll = clamp1(hudRoll);
        DroneState.hudPitch = clamp1(hudPitch);
        DroneState.hudThrottle = clamp1(hudThrottle);
    }

    private static float clamp1(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}