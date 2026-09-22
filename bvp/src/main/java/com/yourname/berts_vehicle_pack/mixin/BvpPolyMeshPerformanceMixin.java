package com.yourname.berts_vehicle_pack.mixin;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.example.sbwmeshloader.core.PolyMesh;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opt-in observation of the mesh-loader hot path without changing loader state or rendering.
 */
@Mixin(value = PolyMesh.class, remap = false)
public abstract class BvpPolyMeshPerformanceMixin {
    @Shadow(remap = false)
    private VertexBuffer geometryVbo;

    @Inject(method = "isVboReady", at = @At("RETURN"), remap = false)
    private void bvp$recordVboReadiness(int packedLight, CallbackInfoReturnable<Boolean> callback) {
        ClientRenderPerformanceDiagnostics.recordPolyMeshVboLookup(callback.getReturnValueZ());
    }

    @Inject(method = "ensureUploaded", at = @At("HEAD"), remap = false)
    private void bvp$recordVboUpload(int packedLight, CallbackInfo callback) {
        if (!ClientRenderPerformanceDiagnostics.isEnabled()) {
            return;
        }
        PolyMesh mesh = (PolyMesh) (Object) this;
        boolean ready = geometryVbo != null && !geometryVbo.m_231230_();
        ClientRenderPerformanceDiagnostics.recordPolyMeshVboLookup(ready);
        if (!ready && mesh.getVertexCount() > 0) {
            long bytes = (long) mesh.getVertexCount() * DefaultVertexFormat.f_85812_.m_86020_();
            ClientRenderPerformanceDiagnostics.recordPolyMeshUpload(bytes);
        }
    }

    @Inject(method = "drawVBOWithShader", at = @At("HEAD"), remap = false)
    private void bvp$recordVboDraw(
            org.joml.Matrix4f matrix, int packedLight, ShaderInstance shader, CallbackInfo callback) {
        if (!ClientRenderPerformanceDiagnostics.isEnabled()) {
            return;
        }
        if (geometryVbo != null && !geometryVbo.m_231230_() && shader != null) {
            ClientRenderPerformanceDiagnostics.recordPolyMeshDraw();
        }
    }
}
