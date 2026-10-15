
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

        if (player == null) return;

        while (TOGGLE_DRONE.consumeClick()) {
            if (DroneState.active) {
                DroneState.exit(player);
            } else {
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

        DroneState.orientation
                .rotateX(-(float) Math.toRadians(
                        mouseY * PITCH_PER_MOUSE_DEG
                ))
                .rotateZ(-(float) Math.toRadians(
                        mouseX * ROLL_PER_MOUSE_DEG
                ))
                .rotateY(-(float) Math.toRadians(
                        yawInput * YAW_RATE_DEG_PER_SEC * dt
                ))
                .normalize();

        // Position is updated by player.move() inside stepPhysics().
        Vec3 position = DroneState.stepPhysics(
                player,
                dt,
                throttleInput
        );

        DroneState.position = position;
    }
}
