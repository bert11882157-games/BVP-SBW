package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMeshModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Bounded render-thread upload seam used by BVP's client-tick model streamer. */
@Mixin(value = PolyMeshModel.class, remap = false)
public interface BvpPolyMeshModelPrewarmAccessor {
    @Invoker(value = "advanceGeometryUpload", remap = false)
    boolean bvp$advanceGeometryUpload(int packedLight, int maxMeshes, long maxNanos);
}
