package com.taylorgoolsby.fpvdrone.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.taylorgoolsby.fpvdrone.DroneDebug;
import com.taylorgoolsby.fpvdrone.DroneHeadRender;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves a drone player's head to the center of their hitbox, and turns the
 * local player's head with the drone camera.
 *
 * AvatarRenderer.scale() runs inside the model's push/pop, after the
 * rotation and flip and before the translate down to the model origin, and
 * outside the nametag, so the nametag keeps its place above the hitbox.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(
            method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("TAIL")
    )
    private void fpvdrone$centerHead(
            AvatarRenderState state,
            PoseStack poseStack,
            CallbackInfo ci
    ) {
        boolean drone = DroneHeadRender.isDroneAvatar(state);
        DroneDebug.markHook(drone);

        if (drone) {
            DroneHeadRender.positionHead(state, poseStack);
        }
    }
}