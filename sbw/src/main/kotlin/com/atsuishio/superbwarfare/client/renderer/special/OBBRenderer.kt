package com.atsuishio.superbwarfare.client.renderer.special

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LevelRenderer
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d

/**
 * Codes based on @AnECanSaiTin's [HitboxAPI](https://github.com/AnECanSaiTin/HitboxAPI)
 */
object OBBRenderer {
    fun render(
        entity: VehicleEntity,
        obbList: MutableList<OBB>,
        poseStack: PoseStack,
        buffer: VertexConsumer,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        pPartialTicks: Float
    ) {
        val providerPresentation = entity.resolveVehiclePoseProvider() != null &&
            !entity.flightStrategyOwnsAttitudeThisTick
        val renderedObbs = if (providerPresentation) {
            entity.obb.mapIndexedNotNull { index, info ->
                val authoritative = obbList.getOrNull(index) ?: return@mapIndexedNotNull null
                val transform = entity.getTransformFromString(info.transform, pPartialTicks)
                val world = entity.transformPosition(transform, info.position.x, info.position.y, info.position.z)
                OBB(
                    Vector3d(world.x, world.y, world.z),
                    Vector3d(authoritative.extents),
                    transform.getNormalizedRotation(Quaterniond()),
                    authoritative.part,
                )
            }
        } else {
            obbList
        }
        // EntityRenderDispatcher's debug PoseStack is rooted at vanilla interpolated position.
        // Presentation copies use the resolved chassis sample but never mutate gameplay OBBs.
        val position = entity.getLegacyInterpolatedPosition(pPartialTicks)
        for (obb in renderedObbs) {
            val center = obb.center
            val halfExtents = obb.extents
            val rotation = obb.rotation
            if (obb.part == OBB.Part.INTERACTIVE) {
                renderOBB(
                    poseStack, buffer,
                    center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                    rotation,
                    halfExtents.x(), halfExtents.y(), halfExtents.z(),
                    1f, 0.8f, 0f, 1f
                )
            } else {
                renderOBB(
                    poseStack, buffer,
                    center.x() - position.x(), center.y() - position.y(), center.z() - position.z(),
                    rotation,
                    halfExtents.x(), halfExtents.y(), halfExtents.z(),
                    red, green, blue, alpha
                )
            }
        }
    }

    fun renderOBB(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        rotation: Quaterniond,
        halfX: Double,
        halfY: Double,
        halfZ: Double,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    ) {
        poseStack.pushPose()
        poseStack.translate(centerX, centerY, centerZ)
        poseStack.mulPose(Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w))
        LevelRenderer.renderLineBox(
            poseStack,
            buffer,
            -halfX,
            -halfY,
            -halfZ,
            halfX,
            halfY,
            halfZ,
            red,
            green,
            blue,
            alpha
        )
        poseStack.popPose()
    }
}
