package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.FarTerrainClient;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** All terrain and entities must use the same depth mapping for reliable occlusion. */
@Mixin(GameRenderer.class)
public abstract class FarTerrainProjectionMixin {
    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void sbw$terrainProjection(double fov, CallbackInfoReturnable<Matrix4f> result) {
        int radius = FarTerrainClient.renderRadius();
        if (radius <= 0) return;
        Matrix4f original = result.getReturnValue();
        float near = original.m32() / (original.m22() - 1F);
        float far = radius + 512F;
        if (Float.isFinite(near) && near > 0 && far > near) {
            result.setReturnValue(new Matrix4f(original).m22(-(far + near) / (far - near))
                    .m32(-(2F * far * near) / (far - near)));
        }
    }
}
