package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;

/**
 * Client-only. Draws the stick-input overlay: two 2D crosshairs that mimic
 * the gimbals of a Mode 2 RC transmitter.
 *
 *   left  : throttle (vertical) + yaw (horizontal)
 *   right : pitch    (vertical) + roll (horizontal)
 *
 * The values it shows are written by DroneClientEvents each frame into
 * DroneState.hudThrottle / hudYaw / hudPitch / hudRoll.
 */
public final class DroneHud {

    private static final Identifier LAYER_ID =
            Identifier.fromNamespaceAndPath(FpvDroneMod.MODID, "stick_overlay");

    // Layout, in GUI-scaled pixels.
    private static final int BOX_SIZE = 56;     // outer size of each gimbal box
    private static final int MARGIN_X = 14;     // distance from screen edge
    private static final int MARGIN_Y = 14;     // distance from screen bottom
    private static final int MARKER_SIZE = 5;   // the moving square (odd = centered)

    // ARGB colors.
    private static final int COLOR_BACKGROUND = 0x66000000;
    private static final int COLOR_BORDER = 0xAAFFFFFF;
    private static final int COLOR_CROSSHAIR = 0x99FFFFFF;
    private static final int COLOR_MARKER_OUTLINE = 0xFF000000;
    private static final int COLOR_MARKER = 0xFFFF3B30;

    private DroneHud() {}

    public static void register() {
        AddGuiOverlayLayersEvent.BUS.addListener(DroneHud::onAddLayers);
    }

    private static void onAddLayers(AddGuiOverlayLayersEvent event) {
        // Added to the end of the root stack, so it draws on top of vanilla's
        // layers. Only shown while flying.
        event.getLayeredDraw().addWithCondition(
                LAYER_ID,
                DroneHud::render,
                () -> DroneState.active
        );
    }

    private static void render(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        int top = height - MARGIN_Y - BOX_SIZE;

        // Left gimbal: throttle (up/down) + yaw (left/right).
        drawGimbal(gg, MARGIN_X, top,
                DroneState.hudYaw, DroneState.hudThrottle);

        // Right gimbal: pitch (up/down) + roll (left/right).
        // Pitch is +1 for stick forward, which is up on screen.
        drawGimbal(gg, width - MARGIN_X - BOX_SIZE, top,
                DroneState.hudRoll, DroneState.hudPitch);
    }

    /**
     * @param x  stick X, -1 (left) .. +1 (right)
     * @param y  stick Y, -1 (down) .. +1 (up)
     */
    private static void drawGimbal(
            GuiGraphicsExtractor gg, int left, int top, float x, float y
    ) {
        int right = left + BOX_SIZE;
        int bottom = top + BOX_SIZE;

        // Background and 1px border.
        gg.fill(left, top, right, bottom, COLOR_BACKGROUND);
        gg.fill(left, top, right, top + 1, COLOR_BORDER);
        gg.fill(left, bottom - 1, right, bottom, COLOR_BORDER);
        gg.fill(left, top, left + 1, bottom, COLOR_BORDER);
        gg.fill(right - 1, top, right, bottom, COLOR_BORDER);

        // Crosshair axes through the center.
        int cx = left + BOX_SIZE / 2;
        int cy = top + BOX_SIZE / 2;
        gg.fill(left + 1, cy, right - 1, cy + 1, COLOR_CROSSHAIR);
        gg.fill(cx, top + 1, cx + 1, bottom - 1, COLOR_CROSSHAIR);

        // Moving square. Travel is limited so the marker stays inside the
        // border at full deflection.
        int half = MARKER_SIZE / 2;
        float travel = BOX_SIZE / 2f - 1f - half;
        int mx = cx + Math.round(clamp(x) * travel);
        int my = cy - Math.round(clamp(y) * travel);

        gg.fill(mx - half - 1, my - half - 1,
                mx + half + 2, my + half + 2, COLOR_MARKER_OUTLINE);
        gg.fill(mx - half, my - half,
                mx + half + 1, my + half + 1, COLOR_MARKER);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}