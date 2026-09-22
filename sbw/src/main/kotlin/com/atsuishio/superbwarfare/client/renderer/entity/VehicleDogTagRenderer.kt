package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.config.client.DisplayConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.SpritePixelHelper
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.core.animatable.model.CoreGeoBone
import software.bernie.geckolib.util.RenderUtils

/** Renders the optional per-vehicle dog-tag quad without owning vehicle geometry lifecycle. */
internal object VehicleDogTagRenderer {
    fun render(
        poseStack: PoseStack,
        vehicle: VehicleEntity,
        bone: GeoBone,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        vehicleTexture: ResourceLocation,
    ) {
        val icon = vehicle.dogTagIcon
        if (!DisplayConfig.DOG_TAG_ICON_VISIBLE.get() || icon.all { row -> row.all { it == (-1).toShort() } }) {
            return
        }
        val cube = bone.cubes.firstOrNull() ?: return

        poseStack.pushPose()
        try {
            RenderUtils.translateMatrixToBone(poseStack, bone)
            RenderUtils.translateToPivotPoint(poseStack, bone)
            rotateAroundBone(poseStack, bone)
            RenderUtils.scaleMatrixForBone(poseStack, bone)
            RenderUtils.translateAwayFromPivotPoint(poseStack, bone)
            poseStack.translate(bone.pivotX / 16, bone.pivotY / 16, bone.pivotZ / 16)
            poseStack.mulPose(Axis.YP.rotationDegrees(180f))
            poseStack.mulPose(Axis.XP.rotationDegrees(90f))

            val pose = poseStack.last()
            val consumer = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(SpritePixelHelper.getDogTagIcon(icon, vehicle.uuid.toString()))
            )
            val xSize = cube.size.x.toFloat() / 16
            val ySize = cube.size.y.toFloat() / 16
            vertex(consumer, pose.pose(), pose.normal(), packedLight, -0.5f * xSize, -0.5f * ySize, 0, 1)
            vertex(consumer, pose.pose(), pose.normal(), packedLight, 0.5f * xSize, -0.5f * ySize, 1, 1)
            vertex(consumer, pose.pose(), pose.normal(), packedLight, 0.5f * xSize, 0.5f * ySize, 1, 0)
            vertex(consumer, pose.pose(), pose.normal(), packedLight, -0.5f * xSize, 0.5f * ySize, 0, 0)
        } finally {
            poseStack.popPose()
        }

        // Restore the vehicle buffer selected by the outer renderer.
        bufferSource.getBuffer(RenderType.entityTranslucent(vehicleTexture))
    }

    private fun rotateAroundBone(poseStack: PoseStack, bone: CoreGeoBone) {
        if (bone.rotZ != 0f || bone.rotY != 0f || bone.rotX != 0f) {
            poseStack.mulPose(Quaternionf().rotationZYX(bone.rotZ, bone.rotY, bone.rotX))
        }
    }

    private fun vertex(
        consumer: VertexConsumer,
        pose: Matrix4f,
        normal: Matrix3f,
        light: Int,
        x: Float,
        z: Float,
        u: Int,
        v: Int,
    ) {
        consumer.vertex(pose, x, 0f, -z).color(255, 255, 255, 255).uv(u.toFloat(), v.toFloat())
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, 0f, 1f, 0f).endVertex()
    }
}
