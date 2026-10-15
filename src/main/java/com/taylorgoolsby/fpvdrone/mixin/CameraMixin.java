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

    // @Shadow gives us access to Camera's private fields.
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private int matrixPropertiesDirty;

    /**
     * Runs at the very end of Camera.setRotation(yRot, xRot, z).
     * The descriptor (FFF)V picks the 3-float overload, since there is also
     * a protected 2-float one that just delegates to it.
     *
     * Because we hook the end of setRotation itself, it doesn't matter who
     * called it or in what order (vanilla's alignWithEntity, or Forge applying
     * the ComputeCameraAngles result). The drone's orientation always wins.
     */
    @Inject(method = "setRotation(FFF)V", at = @At("TAIL"))
    private void fpvdrone$applyDroneOrientation(float yRot, float xRot, float z, CallbackInfo ci) {
        if (!DroneState.active) return;

        // Replace the Euler-derived rotation with the drone's quaternion.
        this.rotation.set(DroneState.orientation);

        // Recompute the direction vectors exactly as vanilla does.
        new Vector3f(0f, 0f, -1f).rotate(this.rotation, this.forwards);
        new Vector3f(0f, 1f, 0f).rotate(this.rotation, this.up);
        new Vector3f(-1f, 0f, 0f).rotate(this.rotation, this.left);

        // Mark the cached view matrices as stale so they get rebuilt.
        this.matrixPropertiesDirty |= 3;
    }
}