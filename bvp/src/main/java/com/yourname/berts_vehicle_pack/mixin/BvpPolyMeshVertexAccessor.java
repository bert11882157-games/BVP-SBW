package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to immutable, already-baked mesh vertices for optional instancing. */
@Mixin(value = PolyMesh.class, remap = false)
public interface BvpPolyMeshVertexAccessor {
    @Accessor("bakedX") float[] bvp$positionsX();
    @Accessor("bakedY") float[] bvp$positionsY();
    @Accessor("bakedZ") float[] bvp$positionsZ();
    @Accessor("bakedNX") float[] bvp$normalsX();
    @Accessor("bakedNY") float[] bvp$normalsY();
    @Accessor("bakedNZ") float[] bvp$normalsZ();
    @Accessor("bakedU") float[] bvp$textureU();
    @Accessor("bakedV") float[] bvp$textureV();
}
