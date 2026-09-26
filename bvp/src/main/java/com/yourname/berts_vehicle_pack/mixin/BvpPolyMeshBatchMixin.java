package com.yourname.berts_vehicle_pack.mixin;

import com.example.sbwmeshloader.core.PolyMesh;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.yourname.berts_vehicle_pack.client.renderer.BvpMeshBatch;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One shader setup per model pass instead of one per bone mesh (see {@link BvpMeshBatch}). Inside the loader's
 * direct VBO pass the render state is already fixed, so the samplers, projection, fog, colour and program are
 * applied once; each mesh then only uploads its pose (and its mesh-space lights) and draws. The batch closes right
 * after the bone walk, and again (a no-op then) before the pass clears its render state, which also covers an
 * exception thrown inside the walk.
 *
 * <p>Optional injections: if the loader changes shape these do nothing and every mesh draws the old way.</p>
 */
@Mixin(value = PolyMeshModel.class, remap = false)
public abstract class BvpPolyMeshBatchMixin {
    private static final String BONE_WALK = "Lcom/example/sbwmeshloader/core/PolyMeshModel;renderBonesVBO("
            + "Lcom/github/mcmodderanchor/simplebedrockmodel/v1/common/model/BedrockBone;Lcom/mojang/blaze3d/vertex/PoseStack;ZI)V";
    private static final String RENDER_VBO =
            "renderVBO(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/ResourceLocation;ZIF)V";

    @Inject(method = RENDER_VBO, require = 0, remap = false, at = @At(value = "INVOKE", remap = false,
            target = BONE_WALK))
    private void bvp$beginBatch(CallbackInfo callback) {
        BvpMeshBatch.begin();
    }

    @Inject(method = RENDER_VBO, require = 0, remap = false, at = @At(value = "INVOKE", remap = false, shift = At.Shift.AFTER,
            target = BONE_WALK))
    private void bvp$endBatch(CallbackInfo callback) {
        BvpMeshBatch.end();
    }

    /** The exceptional exit: the pass's finally block clears the render state. */
    @Inject(method = RENDER_VBO, require = 0, remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/client/renderer/RenderType;m_110188_()V"))
    private void bvp$endBatchOnClear(CallbackInfo callback) {
        BvpMeshBatch.end();
    }

    @Redirect(method = "renderBonesVBO", require = 0, remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/example/sbwmeshloader/core/PolyMesh;drawVBO(Lorg/joml/Matrix4f;I)V"))
    private void bvp$batchedDraw(PolyMesh mesh, Matrix4f pose, int packedLight) {
        BvpMeshBatch.draw(mesh, pose, packedLight);
    }
}
