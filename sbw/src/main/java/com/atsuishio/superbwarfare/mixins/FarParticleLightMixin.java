package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.FarEffectsClient;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Particle.class)
public abstract class FarParticleLightMixin {
    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void sbw$sampleFarLight(float partialTick, CallbackInfoReturnable<Integer> callback) {
        Integer light = FarEffectsClient.light((Particle) (Object) this);
        if (light != null) callback.setReturnValue(light);
    }
}
