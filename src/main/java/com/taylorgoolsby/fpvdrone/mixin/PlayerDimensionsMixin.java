package com.taylorgoolsby.fpvdrone.mixin;

import com.taylorgoolsby.fpvdrone.DroneState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlayerDimensionsMixin {

    private static final EntityDimensions FPV_DIMENSIONS =
            EntityDimensions.scalable(0.4F, 0.4F).withEyeHeight(0.2F);

    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
    private void fpvdrone$changeDimensions(
            Pose pose,
            CallbackInfoReturnable<EntityDimensions> cir
    ) {
        java.util.UUID id = DroneState.activeUuid;
        if (id != null
                && (Object) this instanceof net.minecraft.world.entity.player.Player p
                && id.equals(p.getUUID())) {
            cir.setReturnValue(FPV_DIMENSIONS);
        }
    }
}