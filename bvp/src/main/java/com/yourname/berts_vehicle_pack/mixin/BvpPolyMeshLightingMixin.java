package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMesh;
import com.yourname.berts_vehicle_pack.client.renderer.BvpVboLighting;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lights the loader's model-local VBO normals correctly (see {@link BvpVboLighting}). */
@Mixin(value = PolyMesh.class, remap = false)
public abstract class BvpPolyMeshLightingMixin {
    @Inject(method = "drawVBOWithShader", at = @At("HEAD"), remap = false)
    private void bvp$meshSpaceLights(org.joml.Matrix4f posePose, int packedLight, ShaderInstance shader,
                                     CallbackInfo callback) {
        BvpVboLighting.enter(posePose);
    }

    @Inject(method = "drawVBOWithShader", at = @At("RETURN"), remap = false)
    private void bvp$levelLights(org.joml.Matrix4f posePose, int packedLight, ShaderInstance shader,
                                 CallbackInfo callback) {
        BvpVboLighting.exit();
    }
}
