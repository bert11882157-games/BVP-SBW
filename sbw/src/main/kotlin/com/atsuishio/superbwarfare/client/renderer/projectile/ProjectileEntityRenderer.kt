package com.atsuishio.superbwarfare.client.renderer.projectile

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailProviders
import com.atsuishio.superbwarfare.client.ClientRenderHandler
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
import com.atsuishio.superbwarfare.tools.localPlayer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos

class ProjectileEntityRenderer(manager: EntityRendererProvider.Context) : EntityRenderer<ProjectileEntity>(manager) {
    private val visualHost = ProjectileVisualRenderHost(manager)
    override fun getTextureLocation(pEntity: ProjectileEntity) = loc("textures/entity/empty.png")

    override fun shouldRender(
        pLivingEntity: ProjectileEntity,
        pCamera: Frustum,
        pCamX: Double,
        pCamY: Double,
        pCamZ: Double
    ): Boolean {
        return true
    }

    // 渲染方式参考 ywzj_vehicle
    // 非常的永无，非常的止境（嗯OC）
    override fun render(
        entity: ProjectileEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (visualHost.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)) return
        // SUPPRESS/REPLACE are immutable per-shot policies.  Never fall through to SBW's
        // default glowing body/trail when the typed belt round owns presentation.
        if (ProjectileTrailProviders.suppressesNativeLaunchFx(entity)) return
        val model = BedrockModelLoader.getModel(BedrockModelLoader.PROJECTILE_MODEL) ?: return
        val eyePos = localPlayer?.eyePosition ?: return
        val renderScale = ProjectileProfiles.resolve(entity)?.renderScale ?: 1f
        val width = 0.3f * renderScale
        val position = entity.getPosition(partialTick)
        val distance = position.distanceTo(eyePos)
        if (entity.tickCount < 5 && distance <= 6.0) return
        // RenderScale is the profile-controlled body thickness. Preserve the deployed
        // migrated behavior: velocity-derived streak length is independent of thickness.
        val length = 0.7 * entity.deltaMovement.length()

        poseStack.pushPose()
        try {
            ClientRenderHandler.markBulletRenderVisible(entity, partialTick)
            ClientRenderHandler.transformVirtualRenderPosition(poseStack, entity, partialTick)
            poseStack.mulPose(Axis.YP.rotationDegrees(VehicleVecUtils.getYRotFromVector(entity.deltaMovement).toFloat()))
            poseStack.mulPose(Axis.XP.rotationDegrees(-VehicleVecUtils.getXRotFromVector(entity.deltaMovement).toFloat()))
            poseStack.scale(width, width, length.toFloat())

            // one shared render type: a fresh energySwirl per round broke the batch into a draw call per round
            val type = RENDER_TYPE
            model.renderToBuffer(
                poseStack,
                buffer.getBuffer(type),
                packedLight,
                OverlayTexture.NO_OVERLAY,
                entity.getEntityData().get(ProjectileEntity.COLOR_R),
                entity.getEntityData().get(ProjectileEntity.COLOR_G),
                entity.getEntityData().get(ProjectileEntity.COLOR_B),
                1.0f
            )
        } finally {
            poseStack.popPose()
        }
    }

    override fun getBlockLightLevel(pEntity: ProjectileEntity, pPos: BlockPos): Int = 15

    companion object {
        val TEXTURE = loc("textures/bedrock/projectile/projectile.png")
        private val RENDER_TYPE: RenderType by lazy { RenderType.energySwirl(TEXTURE, 15.0f, 15.0f) }
    }
}
