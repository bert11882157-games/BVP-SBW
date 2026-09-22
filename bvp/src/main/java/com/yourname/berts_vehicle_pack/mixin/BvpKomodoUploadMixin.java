package com.yourname.berts_vehicle_pack.mixin;

import com.yourname.berts_vehicle_pack.client.renderer.BvpKomodoBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Submits current render poses after parallel compaction and before Flywheel uploads instances. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.EngineImpl", remap = false)
public abstract class BvpKomodoUploadMixin {
    @Inject(method = "render(Ldev/engine_room/flywheel/api/backend/RenderContext;)V",
            at = @At("HEAD"), remap = false)
    private void bvp$flushMeshInstances(CallbackInfo callback) {
        BvpKomodoBridge.flush();
    }
}
