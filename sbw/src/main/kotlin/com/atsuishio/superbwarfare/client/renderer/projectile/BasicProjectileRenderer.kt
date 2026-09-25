package com.atsuishio.superbwarfare.client.renderer.projectile

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.entity.projectile.BasicGeoProjectileEntity
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
import com.maydaymemory.mae.basic.ArrayPoseBuilder
import com.maydaymemory.mae.basic.ZYXBoneTransformFactory
import com.maydaymemory.mae.blend.EulerAdditiveBlender
import com.maydaymemory.mae.blend.SimpleEulerAdditiveBlender
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import java.util.WeakHashMap

open class BasicProjectileRenderer<T>(manager: EntityRendererProvider.Context) :
    EntityRenderer<T>(manager) where T : Entity, T : BasicGeoProjectileEntity {
    private val visualHost = ProjectileVisualRenderHost(manager)
    private val diagnosticSamples = WeakHashMap<Entity, Double>()

    private fun recordRendered(entity: T, partialTick: Float) {
        if (!java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios") || !EliteDiagnostics.isClientEnabled()) return
        val renderTick = entity.level().gameTime.toDouble() + partialTick
        if (renderTick - (diagnosticSamples[entity] ?: Double.NEGATIVE_INFINITY) < 0.25) return
        diagnosticSamples[entity] = renderTick
        EliteDiagnostics.record(entity, "elite_flight", "PROJECTILE_RENDERED",
            "render_tick", renderTick, "partial_tick", partialTick, "position", entity.getPosition(partialTick))
    }
    override fun getTextureLocation(entity: T): ResourceLocation {
        return loc("textures/bedrock/projectile/${entity.type.descriptionId.split(".")[2]}.png")
    }

    override fun shouldShowName(pEntity: T): Boolean {
        return false
    }

    override fun render(
        entity: T,
        yaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (visualHost.render(entity, yaw, partialTick, poseStack, buffer, packedLight)) {
            recordRendered(entity, partialTick)
            return
        }
        if (entity.tickCount <= entity.getHiddenTicks()) return
        val model = BedrockModelLoader.getModel(entity.getModel()) ?: return

        poseStack.pushPose()

        poseStack.translate(0f, entity.bbHeight / 2, 0f)

        //十分鬼畜而神秘的写法，直接用yaw的话会导致弹体在+-180°偏航时抽搐，遂采用这种脱裤子放屁的写法
        // Heading blended between ticks (wrap-aware yaw), so guided rounds turn smoothly instead of once per tick.
        val look = net.minecraft.world.phys.Vec3.directionFromRotation(
            net.minecraft.util.Mth.lerp(partialTick, entity.xRotO, entity.xRot),
            net.minecraft.util.Mth.rotLerp(partialTick, entity.yRotO, entity.yRot))
            .takeIf { it.lengthSqr() > 1.0e-6 } ?: entity.lookAngle
        poseStack.mulPose(Axis.YP.rotationDegrees(VehicleVecUtils.getYRotFromVector(look).toFloat()))
        poseStack.mulPose(Axis.XP.rotationDegrees(-VehicleVecUtils.getXRotFromVector(look).toFloat() + 180f))

        val renderType = RenderType.entityTranslucent(getTextureLocation(entity))
        val vertexConsumer = buffer.getBuffer(renderType)

        if (entity.getAnimationInstance() != null) {
            val ani = entity.getAnimationInstance()!!
            ani.context.partialTick = partialTick
            ani.tick()
            model.applyPose(BLENDER.blend(model.bindPose, ani.getPose()))
        }

        val flare = model.getBone("flare")
        val flag = flare != null
        if (flag) {
            flare.visible = false
        }

        model.renderToBuffer(
            poseStack,
            vertexConsumer,
            packedLight,
            OverlayTexture.NO_OVERLAY
        )
        recordRendered(entity, partialTick)

        val texture = entity.getEmissiveTexture()
        if (texture != null) {
            model.renderToBuffer(
                poseStack,
                buffer.getBuffer(RenderType.eyes(texture)),
                packedLight,
                OverlayTexture.NO_OVERLAY
            )
        }

        if (flag && entity.tickCount > entity.getFlareHiddenTicks()) {
            flare.visible = true
            flare.rotation.rotationZ(2.5f * (Math.random().toFloat() - 0.5f))
            flare.xScale = ((2 * Math.random() - 1) * 0.4f + 1.6).toFloat()
            flare.yScale = ((2 * Math.random() - 1) * 0.4f + 1.6).toFloat()
            flare.zScale = ((2 * Math.random() - 1) * 0.4f + 1.6).toFloat()
            flare.render(
                poseStack,
                buffer.getBuffer(RenderType.eyes(FLARE_TEXTURE)),
                packedLight,
                OverlayTexture.NO_OVERLAY
            )
        }

        poseStack.popPose()
    }

    companion object {
        val BLENDER: EulerAdditiveBlender = SimpleEulerAdditiveBlender(ZYXBoneTransformFactory()) { ArrayPoseBuilder() }
        val FLARE_TEXTURE = loc("textures/bedrock/projectile/flare.png")
    }
}
