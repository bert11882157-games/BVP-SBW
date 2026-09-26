package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMesh;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The mesh's immutable geometry VBO, for the batched draw in {@code BvpMeshBatch}. */
@Mixin(value = PolyMesh.class, remap = false)
public interface BvpPolyMeshDrawAccessor {
    @Accessor(value = "geometryVbo", remap = false)
    VertexBuffer bvp$geometryVbo();
}
