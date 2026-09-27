package com.yourname.berts_vehicle_pack.mixin;

import com.atsuishio.superbwarfare.diagnostics.FramePhases;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Perf probe only: counts and times Flywheel's full mesh-pool re-uploads (one flag check when no capture runs). */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.MeshPool", remap = false)
public abstract class BvpMeshPoolProbeMixin {
    private static long bvp$uploadStart;

    @Inject(method = "uploadAll", at = @At("HEAD"), remap = false, require = 0)
    private void bvp$uploadStart(CallbackInfo callback) {
        if (FramePhases.active) bvp$uploadStart = System.nanoTime();
    }

    @Inject(method = "uploadAll", at = @At("RETURN"), remap = false, require = 0)
    private void bvp$uploadEnd(CallbackInfo callback) {
        if (!FramePhases.active) return;
        FramePhases.meshUploads++;
        FramePhases.meshUploadNanos += System.nanoTime() - bvp$uploadStart;
    }
}
