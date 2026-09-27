package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.particle.SoftParticleRenderType;
import net.minecraft.client.particle.CampfireSmokeParticle;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Campfire smoke (explosion smoke columns and ground smoke, rocket and missile trails, wreck smoke) draws on the
 * translucent sheet, which writes depth for every visible texel. The blast fireballs and afterburner plumes are drawn
 * after the particles, so a thin smoke puff anywhere in front of a fireball hid it completely. Drawn soft (blended,
 * no depth write) the fireball shows through the smoke. Smoke is grey and nearly uniform, so the unsorted blending
 * between smoke puffs is not visible.
 */
@Mixin(CampfireSmokeParticle.class)
public abstract class CampfireSmokeParticleMixin {
    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true, require = 0)
    private void superbwarfare$softRenderType(CallbackInfoReturnable<ParticleRenderType> cir) {
        cir.setReturnValue(SoftParticleRenderType.INSTANCE);
    }
}
