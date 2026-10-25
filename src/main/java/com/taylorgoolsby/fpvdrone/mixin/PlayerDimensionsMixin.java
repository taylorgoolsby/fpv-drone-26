package com.taylorgoolsby.fpvdrone.mixin;

import com.taylorgoolsby.fpvdrone.DroneRegistry;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlayerDimensionsMixin {

    private static final EntityDimensions FPV_DIMENSIONS =
            EntityDimensions.scalable(0.3F, 0.3F).withEyeHeight(0.15F);

    @Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
    private void fpvdrone$changeDimensions(
            Pose pose,
            CallbackInfoReturnable<EntityDimensions> cir
    ) {
        if ((Object) this instanceof Player p
                && DroneRegistry.isDrone(p.getUUID())) {
            cir.setReturnValue(FPV_DIMENSIONS);
        }
    }
}