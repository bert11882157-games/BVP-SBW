package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.particle.FxLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities and vehicles take light from nearby effects (fireballs, muzzle flashes, afterburners): see FxLights. */
@Mixin(EntityRenderer.class)
public abstract class FxEntityLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private void superbwarfare$effectLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int effect = FxLights.levelAt(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
        if (effect > cir.getReturnValueI()) cir.setReturnValue(effect);
    }
}
