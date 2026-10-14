package com.taylorgoolsby.fpvdrone;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;

/**
 * All client-side event handlers for the drone mod.
 *
 * In Forge 26.x every event class owns its own static BUS, so we subscribe
 * with EventBusType.BUS.addListener(...) instead of picking a MOD or FORGE bus.
 */
public class DroneClientEvents {

    /** Heading for our key in Options > Controls. Must be registered exactly once. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("fpvdrone", "main"));

    /** Rebindable toggle key. 12 = SDL scancode for I. */
    public static final KeyMapping TOGGLE_DRONE = new KeyMapping(
            "key.fpvdrone.toggle",
            InputConstants.Type.KEYBOARD,
            12,
            CATEGORY
    );

    /**
     * Call this once from your main mod class (client side only).
     * It subscribes every handler to its event's bus.
     */
    public static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(DroneClientEvents::onRegisterKeys);
        TickEvent.ClientTickEvent.Post.BUS.addListener(DroneClientEvents::onClientTick);
        ViewportEvent.ComputeCameraAngles.BUS.addListener(DroneClientEvents::onCameraAngles);
    }

    /** Fires during startup while Minecraft collects key bindings. */
    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_DRONE);
    }

    /** Fires at the end of every client tick (20 times per second). */
    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return; // title screen / loading

        // consumeClick() is true once per key press; the loop drains queued presses.
        while (TOGGLE_DRONE.consumeClick()) {
            if (DroneState.active) {
                DroneState.exit();
            } else {
                DroneState.enter(mc.player.getYRot(), mc.player.getXRot());
            }
        }
    }

    /**
     * Fires every frame while the camera angles are computed.
     * Replaces vanilla's angles with the drone's.
     */
    private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!DroneState.active) return;

        event.setYaw(DroneState.yaw);
        event.setPitch(DroneState.pitch);
        event.setRoll(DroneState.roll);
    }
}