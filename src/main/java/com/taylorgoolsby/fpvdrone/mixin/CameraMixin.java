package com.taylorgoolsby.fpvdrone.mixin;

import com.taylorgoolsby.fpvdrone.DroneState;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;

    @Inject(method = "setRotation(FFF)V", at = @At("TAIL"))
    private void fpvdrone$applyDroneOrientation(float yRot, float xRot, float z, CallbackInfo ci) {
        if (!DroneState.active) return;

        // Camera = drone body tilted up by the camera angle.
        DroneState.getCameraOrientation(this.rotation);

        new Vector3f(0f, 0f, -1f).rotate(this.rotation, this.forwards);
        new Vector3f(0f, 1f, 0f).rotate(this.rotation, this.up);
        new Vector3f(-1f, 0f, 0f).rotate(this.rotation, this.left);

        this.matrixPropertiesDirty |= 3;
    }
}