package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;

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
    private static final int COLOR_CROSSHAIR = 0x99FFFFFF;
    private static final int COLOR_MARKER_BORDER = COLOR_CROSSHAIR;

    private DroneHud() {}

    public static void register() {
        AddGuiOverlayLayersEvent.BUS.addListener(DroneHud::onAddLayers);
    }

    private static void onAddLayers(AddGuiOverlayLayersEvent event) {
        hideVanillaLayersWhileFlying(event.getLayeredDraw());

        // Added to the end of the root stack, so it draws on top of vanilla's
        // layers. Only shown while flying.
        event.getLayeredDraw().addWithCondition(
                LAYER_ID,
                DroneHud::render,
                () -> DroneState.active
        );
    }

    /**
     * Attaches a "not flying" condition to the vanilla HUD layers, so they are
     * skipped while in drone mode. Layers are matched by Forge's stack and
     * layer ids. Left visible: camera overlays (vignette, underwater, etc.),
     * chat, tab list, debug overlay and subtitles.
     */
    private static void hideVanillaLayersWhileFlying(ForgeLayeredDraw draw) {
        // Top level pre-sleep stack.
        hide(draw, ForgeLayeredDraw.PRE_SLEEP_STACK,
                ForgeLayeredDraw.CROSSHAIR,
                ForgeLayeredDraw.POTION_EFFECTS,
                ForgeLayeredDraw.BOSS_OVERLAY);

        // Hotbar cluster: hotbar (and offhand slot), health, vehicle health,
        // XP level, XP / locator / jump bars, selected item name.
        hide(draw, ForgeLayeredDraw.HOTBAR_AND_DECOS,
                ForgeLayeredDraw.SPECTATOR_HOTBAR,
                ForgeLayeredDraw.ITEM_HOTBAR,
                ForgeLayeredDraw.HEALTH_BAR,
                ForgeLayeredDraw.VEHICLE_HEALTH,
                ForgeLayeredDraw.BACKGROUND,        // contextual bar background
                ForgeLayeredDraw.EXPERIENCE_LEVEL,
                ForgeLayeredDraw.CONTEXTUAL_INFO,   // XP bar / locator bar / jump bar
                ForgeLayeredDraw.SELECTED_ITEM_NAME,
                ForgeLayeredDraw.SPECTATOR_ACTION);

        // Post-sleep stack.
        hide(draw, ForgeLayeredDraw.POST_SLEEP_STACK,
                ForgeLayeredDraw.DEMO_OVERLAY,
                ForgeLayeredDraw.SCOREBOARD,
                ForgeLayeredDraw.HOTBAR_MESSAGE,
                ForgeLayeredDraw.TITLE_OVERLAY);
    }

    private static void hide(ForgeLayeredDraw draw, Identifier stack, Identifier... layers) {
        for (Identifier layer : layers) {
            draw.addConditionTo(stack, layer, () -> !DroneState.active);
        }
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

        // Crosshair axes through the center (no background, no border).
        int cx = left + BOX_SIZE / 2;
        int cy = top + BOX_SIZE / 2;
        gg.fill(left, cy, right, cy + 1, COLOR_CROSSHAIR);
        gg.fill(cx, top, cx + 1, bottom, COLOR_CROSSHAIR);

        // Moving square: hollow, with a 1px border in the crosshair color. Travel is limited
        // so the marker stays inside the axes at full deflection.
        int half = MARKER_SIZE / 2 + 1; // outer half-size, includes the border
        float travel = BOX_SIZE / 2f - 1f - half;
        int mx = cx + Math.round(clamp(x) * travel);
        int my = cy - Math.round(clamp(y) * travel);

        int l = mx - half;
        int r = mx + half + 1;
        int t = my - half;
        int bt = my + half + 1;
        gg.fill(l, t, r, t + 1, COLOR_MARKER_BORDER);       // top
        gg.fill(l, bt - 1, r, bt, COLOR_MARKER_BORDER);     // bottom
        gg.fill(l, t + 1, l + 1, bt - 1, COLOR_MARKER_BORDER); // left
        gg.fill(r - 1, t + 1, r, bt - 1, COLOR_MARKER_BORDER); // right
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}