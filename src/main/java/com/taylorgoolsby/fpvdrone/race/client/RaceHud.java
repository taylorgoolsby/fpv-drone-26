package com.taylorgoolsby.fpvdrone.race.client;

import com.taylorgoolsby.fpvdrone.FpvDroneMod;
import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import com.taylorgoolsby.fpvdrone.race.RaceTime;
import com.taylorgoolsby.fpvdrone.race.net.GateBox;
import com.taylorgoolsby.fpvdrone.race.net.LeaderboardEntry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;

import java.util.List;
import java.util.UUID;

/**
 * Client-only. Draws the racing HUD while racing:
 *
 *   - the track name and the current lap time
 *   - the last lap and the next gate number
 *   - the leaderboard: each player's fastest lap on this track
 *   - a marker on the next gate (the locator bar shows it too, see RaceWaypoints)
 *
 * While building a track, a short reminder with the number of gates added.
 */
final class RaceHud {

    private static final Identifier LAYER_ID =
            Identifier.fromNamespaceAndPath(FpvDroneMod.MODID, "race_hud");

    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_MARGIN = 6;
    private static final int PAD = 4;
    private static final int LINE_HEIGHT = 10;

    // ARGB colors.
    private static final int COLOR_PANEL = 0x88000000;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int COLOR_TIMER = 0xFFFFD166;
    private static final int COLOR_SELF = 0xFF55FF55;
    private static final int COLOR_LEADER = 0xFFFFD166;
    private static final int COLOR_MARKER = 0xFFFFD166;
    private static final int COLOR_MARKER_OUTLINE = 0xFF172028;

    private static final int MARKER_RADIUS = 6;

    private RaceHud() {}

    static void register() {
        AddGuiOverlayLayersEvent.BUS.addListener(RaceHud::onAddLayers);
    }

    private static void onAddLayers(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().addWithCondition(
                LAYER_ID,
                RaceHud::render,
                () -> RaceClientState.racing || RaceClientState.buildMode
        );
    }

    private static void render(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (RaceClientState.racing) {
            drawRacingPanel(gg, mc);
            drawGateMarker(gg, mc, deltaTracker.getGameTimeDeltaPartialTick(false));
        } else if (RaceClientState.buildMode) {
            drawBuildPanel(gg, mc);
        }
    }

    // ---------------------------------------------------------------- panels

    private static void drawBuildPanel(GuiGraphicsExtractor gg, Minecraft mc) {
        int width = mc.getWindow().getGuiScaledWidth();
        int left = width - PANEL_WIDTH - PANEL_MARGIN;
        int top = PANEL_MARGIN;
        gg.fill(left, top, left + PANEL_WIDTH, top + PAD * 2 + LINE_HEIGHT * 2, COLOR_PANEL);
        text(gg, mc.font, "Building a track", left + PAD, top + PAD, COLOR_TIMER);
        text(gg, mc.font, RaceClientState.buildGates.size() + " gates added", left + PAD, top + PAD + LINE_HEIGHT, COLOR_TEXT);
    }

    private static void drawRacingPanel(GuiGraphicsExtractor gg, Minecraft mc) {
        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int left = width - PANEL_WIDTH - PANEL_MARGIN;
        int top = PANEL_MARGIN;

        List<LeaderboardEntry> board = RaceClientState.leaderboard;
        int lines = 4 + (board.isEmpty() ? 0 : 1 + board.size());
        gg.fill(left, top, left + PANEL_WIDTH, top + PAD * 2 + LINE_HEIGHT * lines + 2, COLOR_PANEL);

        int x = left + PAD;
        int y = top + PAD;

        text(gg, font, RaceClientState.trackName, x, y, COLOR_TEXT);
        y += LINE_HEIGHT;

        // Current lap time.
        long start = RaceClientState.lapStartMillis;
        String lap = start == 0 ? "--:--.--" : RaceTime.format(System.currentTimeMillis() - start);
        text(gg, font, "Lap  " + lap, x, y, COLOR_TIMER);
        y += LINE_HEIGHT;

        int last = RaceClientState.lastLapMillis;
        text(gg, font, "Last " + (last < 0 ? "--:--.--" : RaceTime.format(last)), x, y, COLOR_DIM);
        y += LINE_HEIGHT;

        int total = RaceClientState.gates.size();
        String gateText = start == 0
                ? "Fly through gate 1 to start"
                : "Next gate " + (RaceClientState.nextGate + 1) + " / " + total;
        text(gg, font, gateText, x, y, COLOR_DIM);
        y += LINE_HEIGHT;

        if (!board.isEmpty()) {
            y += 2;
            text(gg, font, "Best laps", x, y, COLOR_DIM);
            y += LINE_HEIGHT;
            UUID self = mc.player.getUUID();
            for (int i = 0; i < board.size(); i++) {
                LeaderboardEntry e = board.get(i);
                int color = e.userId().equals(self) ? COLOR_SELF : (i == 0 ? COLOR_LEADER : COLOR_TEXT);
                String time = RaceTime.format(e.millis());
                int maxNameWidth = PANEL_WIDTH - PAD * 2 - font.width(time) - 24;
                text(gg, font, (i + 1) + ". " + fit(font, e.name(), maxNameWidth), x, y, color);
                text(gg, font, time, left + PANEL_WIDTH - PAD - font.width(time), y, color);
                y += LINE_HEIGHT;
            }
        }
    }

    // ---------------------------------------------------------------- waypoint

    private static void drawGateMarker(GuiGraphicsExtractor gg, Minecraft mc, float partialTick) {
        GateBox gate = RaceClientState.getNextGateBox();
        if (gate == null) return;
        if (!RaceClientState.dimension.equals(mc.level.dimension().identifier().toString())) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        RaceProjection.Result r = RaceProjection.project(
                gate.centerX(), gate.centerY(), gate.centerZ(), partialTick, w, h, MARKER_RADIUS + 14f);
        if (r == null) return;

        int x = Math.round(r.x());
        int y = Math.round(r.y());
        drawDiamond(gg, x, y, MARKER_RADIUS + 1, COLOR_MARKER_OUTLINE);
        drawDiamond(gg, x, y, MARKER_RADIUS, COLOR_MARKER);

        String label = Math.round(r.distance()) + " m";
        Font font = mc.font;
        int labelX = x - font.width(label) / 2;
        int labelY = y + MARKER_RADIUS + 4;
        if (labelY + font.lineHeight > h) labelY = y - MARKER_RADIUS - 4 - font.lineHeight;
        text(gg, font, label, labelX, labelY, COLOR_TEXT);
    }

    private static void drawDiamond(GuiGraphicsExtractor gg, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            gg.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Shortens a string until it fits in the given pixel width. */
    private static String fit(Font font, String s, int maxWidth) {
        while (s.length() > 1 && font.width(s) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void text(GuiGraphicsExtractor gg, Font font, String s, int x, int y, int color) {
        gg.text(font, Component.literal(s).getVisualOrderText(), x, y, color);
    }
}
