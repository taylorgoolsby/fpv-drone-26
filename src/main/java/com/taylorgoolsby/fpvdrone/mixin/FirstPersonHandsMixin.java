package com.taylorgoolsby.fpvdrone.mixin;

import com.taylorgoolsby.fpvdrone.DroneState;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the first-person arms and held items (blocks, tools, a shield or
 * anything in the offhand, maps) while flying the drone.
 *
 * submitHandsWithItems() submits both hands: the empty-hand arm, held items,
 * maps and shields. Skipping it removes all of them. The method name is
 * matched without a descriptor and the handler takes no target arguments, so
 * it survives signature changes.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void fpvdrone$hideHands(CallbackInfo ci) {
        if (DroneState.active) {
            ci.cancel();
        }
    }
}