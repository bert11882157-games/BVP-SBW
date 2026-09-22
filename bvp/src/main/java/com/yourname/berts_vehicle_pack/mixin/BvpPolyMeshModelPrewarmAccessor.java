package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.example.sbwmeshloader.core.PolyMesh;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded render-thread upload seam used by BVP's client-tick model streamer. */
@Mixin(value = PolyMeshModel.class, remap = false)
public interface BvpPolyMeshModelPrewarmAccessor {
    @Invoker(value = "advanceGeometryUpload", remap = false)
    boolean bvp$advanceGeometryUpload(int packedLight, int maxMeshes, long maxNanos);

    @Accessor(value = "meshMap", remap = false)
    Map<BedrockBone, List<PolyMesh>> bvp$meshes();

    @Accessor(value = "translucentBones", remap = false)
    Set<BedrockBone> bvp$translucentBones();
}
