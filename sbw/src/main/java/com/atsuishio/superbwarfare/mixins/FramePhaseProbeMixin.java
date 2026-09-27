package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.diagnostics.FramePhases;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Perf probe only: times the client entity tick. One volatile-free flag check per tick when no capture runs. */
@Mixin(ClientLevel.class)
public abstract class FramePhaseProbeMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void sbw$entitiesStart(CallbackInfo ci) {
        if (FramePhases.active) FramePhases.entitiesStart = System.nanoTime();
    }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void sbw$entitiesEnd(CallbackInfo ci) {
        if (FramePhases.active) FramePhases.entitiesNanos += System.nanoTime() - FramePhases.entitiesStart;
    }
}
