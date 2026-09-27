package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.diagnostics.FramePhases;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Perf probe only: times the particle tick. */
@Mixin(ParticleEngine.class)
public abstract class FramePhaseParticleProbeMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void sbw$particlesStart(CallbackInfo ci) {
        if (FramePhases.active) FramePhases.particlesStart = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void sbw$particlesEnd(CallbackInfo ci) {
        if (FramePhases.active) FramePhases.particlesNanos += System.nanoTime() - FramePhases.particlesStart;
    }
}
