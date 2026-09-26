package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.particle.SoftParticleRenderType;
import net.minecraft.client.particle.HugeExplosionParticle;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla explosion puffs draw on the lit sheet, alpha-cut and writing depth, so a puff in front cut every later
 * puff, fire cloud and blast fireball behind it into its square. Drawn soft (blended, no depth write) they overlap.
 */
@Mixin(HugeExplosionParticle.class)
public abstract class HugeExplosionParticleMixin {
    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true, require = 0)
    private void superbwarfare$softRenderType(CallbackInfoReturnable<ParticleRenderType> cir) {
        cir.setReturnValue(SoftParticleRenderType.INSTANCE);
    }
}
