package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.client.renderer.SmartTextureBrightener
import com.atsuishio.superbwarfare.client.renderer.TextureBrightnessHandler
import com.atsuishio.superbwarfare.client.ClientChassisPresentationTelemetry
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendHost
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.core.animatable.GeoAnimatable
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoEntityRenderer
import software.bernie.geckolib.util.RenderUtils


abstract class VehicleRenderer<T>(renderManager: EntityRendererProvider.Context, model: GeoModel<T>) :
    GeoEntityRenderer<T>(renderManager, model) where T : VehicleEntity, T : GeoAnimatable {

    private val vehicleRenderBackendHost = VehicleRenderBackendHost(renderManager)
    private var currentRenderEntity: VehicleEntity? = null
    private var currentRenderFrame: RenderFrame? = null
    private var currentChassisPresentation: VehicleChassisPresentation? = null
    private var currentBackendPass: BackendPass? = null

    override fun getRenderType(
        vehicle: T,
        texture: ResourceLocation,
        bufferSource: MultiBufferSource?,
        partialTick: Float
    ): RenderType? = RenderType.entityTranslucent(getTextureLocation(vehicle))

    override fun render(
        entityIn: T,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        bufferIn: MultiBufferSource,
        packedLightIn: Int
    ) {
        val previousEntity = currentRenderEntity
        val previousFrame = currentRenderFrame
        val previousPresentation = currentChassisPresentation
        currentRenderEntity = entityIn
        val backendId = VehicleResource.getDefault(entityIn).model.geometryBackend
        poseStack.pushPose()
        val presentation = entityIn.resolveChassisPresentation(partialTicks)
        currentChassisPresentation = presentation
        val chassisOffset = presentation.anchor.subtract(entityIn.getLegacyInterpolatedPosition(partialTicks))
        val resolvedEntityYaw = presentation.chassisYawDegrees
        poseStack.translate(chassisOffset.x, chassisOffset.y, chassisOffset.z)
        currentRenderFrame = backendId?.let {
            // The backend restores this sequenced chassis entry before applying its authored axis.
            val entryPose = poseStack.last()
            RenderFrame(
                entityIn,
                it,
                presentation,
                resolvedEntityYaw,
                Matrix4f(entryPose.pose()),
                Matrix3f(entryPose.normal()),
            )
        }
        try {
            ClientChassisPresentationTelemetry.recordRender(entityIn, presentation, partialTicks)
            vehicleAxis(entityIn, poseStack, resolvedEntityYaw, partialTicks)
            super.render(entityIn, resolvedEntityYaw, partialTicks, poseStack, bufferIn, packedLightIn)
        } finally {
            poseStack.popPose()
            currentRenderFrame = previousFrame
            currentChassisPresentation = previousPresentation
            currentRenderEntity = previousEntity
        }
    }

    override fun actuallyRender(
        poseStack: PoseStack,
        animatable: T,
        model: BakedGeoModel,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer?,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        val frame = currentRenderFrame?.takeIf { it.vehicle === animatable }
        val backendId = frame?.backendId ?: if (currentRenderEntity === animatable) {
            null
        } else {
            VehicleResource.getDefault(animatable).model.geometryBackend
        }
        if (backendId == null) {
            val previousPass = currentBackendPass
            currentBackendPass = null
            try {
                super.actuallyRender(
                    poseStack,
                    animatable,
                    model,
                    renderType,
                    bufferSource,
                    buffer,
                    isReRender,
                    partialTick,
                    packedLight,
                    packedOverlay,
                    red,
                    green,
                    blue,
                    alpha,
                )
            } finally {
                currentBackendPass = previousPass
            }
            return
        }

        val preVehicleAxisPose = frame?.preVehicleAxisPose ?: Matrix4f(poseStack.last().pose())
        val preVehicleAxisNormal = frame?.preVehicleAxisNormal ?: Matrix3f(poseStack.last().normal())
        val chassisPresentation = frame?.chassisPresentation
            ?: animatable.resolveChassisPresentation(partialTick)
        val backendPass = BackendPass(
            VehicleRenderBackendContext(
                backendId = backendId,
                vehicle = animatable,
                chassisPresentation = chassisPresentation,
                entityYaw = chassisPresentation.chassisYawDegrees,
                partialTick = partialTick,
                poseStack = poseStack,
                preVehicleAxisPose = Matrix4f(preVehicleAxisPose),
                preVehicleAxisNormal = Matrix3f(preVehicleAxisNormal),
                bufferSource = bufferSource,
                nativeModel = model,
                nativeRenderType = renderType,
                nativeVertexConsumer = buffer,
                resolvedTexture = getTextureLocation(animatable),
                isReRender = isReRender,
                packedLight = packedLight,
                packedOverlay = packedOverlay,
            )
        )
        val previousPass = currentBackendPass
        currentBackendPass = backendPass
        try {
            // GeoEntityRenderer evaluates animations before dispatching the first
            // root bone. renderRecursively performs the backend handoff there.
            super.actuallyRender(
                poseStack,
                animatable,
                model,
                renderType,
                bufferSource,
                buffer,
                isReRender,
                partialTick,
                packedLight,
                packedOverlay,
                red,
                green,
                blue,
                alpha,
            )
        } finally {
            currentBackendPass = previousPass
        }
    }

    override fun renderRecursively(
        poseStack: PoseStack,
        animatable: T,
        bone: GeoBone,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        bufferIn: VertexConsumer?,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    ) {
        val backendPass = currentBackendPass
        if (backendPass != null) {
            if (!backendPass.attempted) {
                backendPass.attempted = true
                backendPass.handled = vehicleRenderBackendHost.render(backendPass.context)
                if (backendPass.handled) {
                    renderNativeBoneLifecycleOnly(
                        poseStack,
                        animatable,
                        backendPass.context.nativeModel.topLevelBones(),
                        backendPass.context.nativeRenderType,
                        bufferSource,
                        backendPass.context.nativeVertexConsumer,
                        backendPass.context.isReRender,
                        backendPass.context.partialTick,
                        packedLight,
                        backendPass.context.packedOverlay,
                    )
                }
            }
            if (backendPass.handled) return
        }

        val name = bone.name
        if (name.endsWith("_dogTag")) {
            bone.isHidden = true
            VehicleDogTagRenderer.render(
                poseStack, animatable, bone, bufferSource, packedLight, getTextureLocation(animatable)
            )
        }

        super.renderRecursively(
            poseStack,
            animatable,
            bone,
            renderType,
            bufferSource,
            bufferIn,
            isReRender,
            partialTick,
            packedLight,
            packedOverlay,
            red,
            green,
            blue,
            alpha
        )
    }

    /** Runs the native bone overlays and dog tags without emitting native cubes. */
    private fun renderNativeBoneLifecycleOnly(
        poseStack: PoseStack,
        animatable: T,
        bones: Iterable<GeoBone>,
        renderType: RenderType?,
        bufferSource: MultiBufferSource,
        buffer: VertexConsumer?,
        isReRender: Boolean,
        partialTick: Float,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        for (bone in bones) {
            poseStack.pushPose()
            if (bone.name.endsWith("_dogTag")) {
                bone.isHidden = true
                VehicleDogTagRenderer.render(
                    poseStack, animatable, bone, bufferSource, packedLight, getTextureLocation(animatable)
                )
            }
            RenderUtils.prepMatrixForBone(poseStack, bone)
            if (!isReRender) {
                applyRenderLayersForBone(
                    poseStack,
                    animatable,
                    bone,
                    renderType,
                    bufferSource,
                    buffer,
                    partialTick,
                    packedLight,
                    packedOverlay,
                )
            }
            if (!bone.isHidingChildren) {
                renderNativeBoneLifecycleOnly(
                    poseStack,
                    animatable,
                    bone.childBones,
                    renderType,
                    bufferSource,
                    buffer,
                    isReRender,
                    partialTick,
                    packedLight,
                    packedOverlay,
                )
            }
            poseStack.popPose()
        }
    }

    open fun vehicleAxis(entityIn: T, poseStack: PoseStack, entityYaw: Float, partialTicks: Float) {
        val root = Vec3(0.0, entityIn.rotateOffsetHeight, 0.0)
        val layeredPose = if (!entityIn.flightStrategyOwnsAttitudeThisTick &&
            entityIn.resolveVehiclePoseProvider() != null
        ) {
            currentChassisPresentation?.pose ?: entityIn.getVehiclePoseSnapshot(partialTicks)
        } else {
            null
        }
        poseStack.rotateAround(
            Axis.YP.rotationDegrees(-entityYaw),
            root.x.toFloat(),
            root.y.toFloat(),
            root.z.toFloat()
        )
        poseStack.rotateAround(
            Axis.XP.rotationDegrees(
                layeredPose?.basePitchDegrees ?: Mth.lerp(partialTicks, entityIn.xRotO, entityIn.xRot)
            ),
            root.x.toFloat(),
            root.y.toFloat(),
            root.z.toFloat()
        )
        poseStack.rotateAround(
            Axis.ZP.rotationDegrees(
                layeredPose?.baseRollDegrees ?: Mth.lerp(partialTicks, entityIn.prevRoll, entityIn.roll)
            ),
            root.x.toFloat(),
            root.y.toFloat(),
            root.z.toFloat()
        )

        if (layeredPose != null && !layeredPose.isExtensionIdentity()) {
            poseStack.translate(0.0, layeredPose.verticalOffset, 0.0)
            poseStack.rotateAround(
                Axis.XP.rotationDegrees(layeredPose.pitchDegrees),
                root.x.toFloat(),
                root.y.toFloat(),
                root.z.toFloat()
            )
            poseStack.rotateAround(
                Axis.ZP.rotationDegrees(layeredPose.rollDegrees),
                root.x.toFloat(),
                root.y.toFloat(),
                root.z.toFloat()
            )
        }
    }

    override fun shouldRender(vehicle: T, pCamera: Frustum, pCamX: Double, pCamY: Double, pCamZ: Double): Boolean {
        if (!vehicle.shouldRender(pCamX, pCamY, pCamZ)) {
            return false
        } else if (vehicle.noCulling) {
            return true
        } else {
            var aabb = vehicle.boundingBoxForCulling.inflate(5.0)
            if (aabb.hasNaN() || aabb.getSize() == 0.0) {
                aabb = AABB(
                    vehicle.x - 8.0,
                    vehicle.y - 6.0,
                    vehicle.z - 8.0,
                    vehicle.x + 8.0,
                    vehicle.y + 6.0,
                    vehicle.z + 8.0
                )
            }

            return pCamera.isVisible(aabb)
        }
    }

    override fun getTextureLocation(animatable: T): ResourceLocation {
        val explicitWreckTexture = if (animatable.isWreck) {
            VehicleResource.getDefault(animatable).model.wreckTexture
        } else {
            null
        }
        val res = explicitWreckTexture ?: super.getTextureLocation(animatable)
        if (ClientEventHandler.activeThermalImaging) {
            return SmartTextureBrightener.getSmartBrightenedTexture(res, 3f)
        } else if (animatable.isWreck && explicitWreckTexture == null) {
            return if ((animatable.vehicleType == VehicleType.AIRPLANE || animatable.vehicleType == VehicleType.HELICOPTER)) {
                if (animatable.sympatheticDetonated) {
                    TextureBrightnessHandler.getBrightenedTexture(res, 0.3f)
                } else {
                    res
                }
            } else {
                TextureBrightnessHandler.getBrightenedTexture(res, 0.3f)
            }
        }
        return res
    }

    private data class RenderFrame(
        val vehicle: VehicleEntity,
        val backendId: ResourceLocation,
        val chassisPresentation: VehicleChassisPresentation,
        val entityYaw: Float,
        val preVehicleAxisPose: Matrix4f,
        val preVehicleAxisNormal: Matrix3f,
    )

    private data class BackendPass(
        val context: VehicleRenderBackendContext,
        var attempted: Boolean = false,
        var handled: Boolean = false,
    )
}
