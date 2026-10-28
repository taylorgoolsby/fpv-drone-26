package com.taylorgoolsby.fpvdrone.race.client;

import com.mojang.blaze3d.framegraph.FramePass;
import com.taylorgoolsby.fpvdrone.FpvDroneMod;
import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import com.taylorgoolsby.fpvdrone.race.net.GateBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;

import java.util.List;

/**
 * Client-only. Draws a white wireframe box around every gate the player has
 * registered while building a track.
 *
 * Uses the same mechanism as Forge's own RenderFrameLayerTest: a frame pass
 * that draws gizmos. The boxes are drawn on top of the world so they stay
 * visible through the terrain.
 */
final class RaceGizmos {

    private static final Identifier PASS_ID =
            Identifier.fromNamespaceAndPath(FpvDroneMod.MODID, "build_gates");

    private static final int WHITE = ARGB.colorFromFloat(1.0F, 1.0F, 1.0F, 1.0F);

    private RaceGizmos() {}

    static void register() {
        AddFramePassEvent.BUS.addListener(RaceGizmos::onAddFramePass);
    }

    private static void onAddFramePass(AddFramePassEvent event) {
        event.addPass(PASS_ID, new FramePassManager.PassDefinition() {
            @Override
            public void extracts(LevelTargetBundle bundle, FramePass pass, LevelRenderState state) {
                bundle.main = pass.readsAndWrites(bundle.main);
            }

            @Override
            public void executes(LevelRenderState state) {
                drawBuildGates();
            }
        });
    }

    private static void drawBuildGates() {
        if (!RaceClientState.buildMode) return;
        List<GateBox> gates = RaceClientState.buildGates;
        if (gates.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (!RaceClientState.buildDimension.equals(mc.level.dimension().identifier().toString())) return;

        GizmoStyle style = GizmoStyle.stroke(WHITE);
        for (GateBox g : gates) {
            // Block coordinates are inclusive, so the far corner is one block further.
            AABB box = new AABB(g.minX(), g.minY(), g.minZ(), g.maxX() + 1, g.maxY() + 1, g.maxZ() + 1);
            Gizmos.cuboid(box, style, true);
        }
    }
}
