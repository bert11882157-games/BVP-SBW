package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.VehicleGeometricZeroDistanceClient
import com.atsuishio.superbwarfare.client.VehicleCcipPresentation
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicator
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicatorDynamic
import com.atsuishio.superbwarfare.client.overlay.VehicleMainWeaponHudOverlay.renderWeaponInfoThird
import com.atsuishio.superbwarfare.client.overlay.weapon.LandVehicleHud
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfile
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfileProvider
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleRole
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidance
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalShotStatus
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.ResourceOnceLogger
import com.atsuishio.superbwarfare.tools.canBeSeen
import com.atsuishio.superbwarfare.tools.toFormattedString
import com.atsuishio.superbwarfare.tools.worldToScreen
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import org.joml.Math
import kotlin.math.roundToInt
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import java.util.HashMap
import java.util.UUID

@OnlyIn(Dist.CLIENT)
object VehicleCrosshairOverlay : CommonOverlay("vehicle_crosshair") {

    private val LOGGER = ResourceOnceLogger()

    val CROSSHAIR_MAP = mapOf(
        "@VehicleUsApc" to loc("textures/overlay/vehicle/crosshair/us_apc.png"),
        "@VehicleUsTank" to loc("textures/overlay/vehicle/crosshair/us_tank.png"),
        "@VehicleRuApc" to loc("textures/overlay/vehicle/crosshair/ru_apc.png"),
        "@VehicleCnTank" to loc("textures/overlay/vehicle/crosshair/cn_tank.png"),
        "@VehicleCommonMissile" to loc("textures/overlay/vehicle/crosshair/common_missile.png"),
        "@VehicleCommonSeekMissile" to loc("textures/overlay/vehicle/crosshair/common_seek_missile.png"),
        "@VehicleCommonGun" to loc("textures/overlay/vehicle/crosshair/common_gun.png"),
        "@VehicleCommonGunDynamic" to loc("textures/overlay/vehicle/crosshair/common_gun.png"),
        "@VehicleCommonCannon" to loc("textures/overlay/vehicle/crosshair/common_cannon.png"),
        "@VehicleCommonCross" to loc("textures/overlay/vehicle/crosshair/common_cross.png"),
        "@VehicleDynamicCross" to loc("textures/overlay/vehicle/crosshair/common_dynamic_cross.png"),
        "@VehicleFixedPoint" to loc("textures/overlay/vehicle/crosshair/common_fixed_point.png"),
        "@VehicleCnHpjZooming" to loc("textures/overlay/vehicle/crosshair/cn_hpj_zooming.png"),
        "@VehicleCommonCannonZooming" to loc("textures/overlay/vehicle/crosshair/common_cannon_zooming.png"),
        "@VehicleLaserCannon" to loc("textures/overlay/vehicle/crosshair/laser_cannon.png"),
        "@AirCraftCommon" to loc("textures/overlay/vehicle/aircraft/common.png"),
        "@NoCross" to loc("textures/overlay/vehicle/crosshair/empty.png")

    )

    private val CROSSHAIR_THIRD_CAMERA = loc("textures/overlay/vehicle/crosshair/third_camera.png")
    private var scopeScale = 1f

    override fun shouldRender(): Boolean {
        val shouldRender = super.shouldRender()
        if (!shouldRender) {
            resetScale()
            ccipScreenSmoother.reset()
        }
        return shouldRender
    }

    override fun RenderContext.render() {
        val entity = player.vehicle
        if (entity !is VehicleEntity) {
            resetScale()
            ccipScreenSmoother.reset()
            return
        }

        val index = entity.getSeatIndex(player)
        val weaponIndex = entity.getPrimaryWeaponIndex(index)
        val reticleProvider = entity as? VehicleAimReticleProfileProvider
        val aimReticleProfile = reticleProvider?.getVehicleAimReticleProfile(index, weaponIndex)
        val reticleRole = reticleProvider?.getVehicleAimReticleRole(index, weaponIndex)
            ?: VehicleAimReticleRole.OTHER
        // Aircraft gun aiming uses only AircraftHud's body-forward circle. Steering intent
        // remains independent, while ground vehicles retain their two physical aim cues.
        val aircraft = entity.isFixedWingFlightVehicle() || entity.vehicleType == VehicleType.AIRPLANE
        if (aircraft) {
            resetScale()
            ccipScreenSmoother.reset()
            return
        }
        val showCrosshairA = reticleRole.showsCameraCommandReticle && !aircraft
        val cameraType = Minecraft.getInstance().options.cameraType
        // Crosshair B is the physical muzzle marker and remains independent of Crosshair A.
        // Keep the typed A suppression intact, but do not suppress B in either third-person
        // camera (including the magnified third-person path, which retains its camera type).
        val thirdPersonCamera = cameraType == CameraType.THIRD_PERSON_BACK ||
                cameraType == CameraType.THIRD_PERSON_FRONT
        val hasAimStateMarker = !aircraft && aimReticleProfile != null &&
                (cameraType == CameraType.FIRST_PERSON || thirdPersonCamera || !showCrosshairA)
        val data = entity.getGunData(index)
        if (data == null) {
            renderCcipIndicator(entity)
            if (hasAimStateMarker) renderAimStateMarker(entity, index)
            resetScale()
            return
        }

        val poseStack = guiGraphics.pose()

        var crosshairPath = data.get(GunProp.CROSSHAIR)
        if (ClientEventHandler.zoomVehicle && data.get(GunProp.CROSSHAIR_ZOOMING) != CrossHairOverlay.CROSSHAIR_EMPTY) {
            crosshairPath = data.get(GunProp.CROSSHAIR_ZOOMING)
        }

        val hasBaseCrosshair = showCrosshairA && crosshairPath != CrossHairOverlay.CROSSHAIR_EMPTY
        if (!hasBaseCrosshair) {
            renderCcipIndicator(entity)
            if (hasAimStateMarker) renderAimStateMarker(entity, index)
            resetScale()
            return
        }

        val color = data.get(GunProp.CROSSHAIR_COLOR).get()

        poseStack.pushPose()

        val recoil = Mth.lerp(partialTick, entity.recoilShakeO.toFloat(), entity.recoilShake.toFloat())
        poseStack.translate(
            LandVehicleHud.lerpRecoil * 6 + screenWidth * 0.025f * recoil,
            recoil * 3 + screenHeight * 0.025f * recoil,
            0f
        )
        poseStack.scale(1 - recoil * 0.05f, 1 - recoil * 0.05f, 1f)
        poseStack.rotateAround(
            Axis.ZP.rotationDegrees(-0.3f * ClientEventHandler.cameraRoll + 4 * LandVehicleHud.lerpRecoil),
            screenWidth / 2f,
            screenHeight / 2f,
            0f
        )

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO
        )
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        scopeScale = Mth.lerp(partialTick, scopeScale, 1f)
        val scale: Float = scopeScale

        val shootPos = entity.getShootPosForHud(player, partialTick)

        val result = player.level().clip(
            ClipContext(
                shootPos, shootPos.add(entity.getShootDirectionForHud(player, partialTick).scale(512.0)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
            )
        )
        val hitPos = result.getLocation()

        var dis = shootPos.distanceTo(hitPos)

        val lookingEntity = entity.getPlayerLookAtEntityOnVehicle(player, 512.0, partialTick)

        if (lookingEntity != null) {
            dis = shootPos.distanceTo(lookingEntity.position())
        }

        val pos = shootPos.add(entity.getShootDirectionForHud(player, partialTick).scale(dis))
        val p = pos.worldToScreen()
        // Ground-vehicle Crosshair A is the HUD/camera-command guide, not a barrel marker.
        // Keep the third-person/F5 projection path below unchanged; the physical muzzle remains
        // Crosshair B in renderAimStateMarker.
        val groundCameraCommandAtCenter = showCrosshairA &&
                entity.resolveVehicleFlightStrategy() == null &&
                entity.vehicleType in GROUND_CCIP_TYPES &&
                Minecraft.getInstance().options.cameraType == CameraType.FIRST_PERSON

        // 渲染第一人称
        if (Minecraft.getInstance().options.cameraType == CameraType.FIRST_PERSON ||
            ClientEventHandler.zoomVehicle
        ) {
            poseStack.pushPose()

            if (hasBaseCrosshair) {
                val texture: ResourceLocation?
                if (crosshairPath.startsWith("@")) {
                    texture = CROSSHAIR_MAP.get(crosshairPath)
                } else {
                    texture = ResourceLocation.tryParse(crosshairPath)
                }

                if (texture == null) {
                    val finalCrosshairPath = crosshairPath
                    if (finalCrosshairPath != "@Custom") {
                        LOGGER.log(
                            crosshairPath
                        ) { logger ->
                            logger!!.error(
                                "Failed to load crosshair texture for {}",
                                finalCrosshairPath
                            )
                        }
                    }
                } else {
                    val minWH = Math.min(screenWidth, screenHeight).toFloat()
                    val scaledMinWH = Mth.floor(minWH * scale).toFloat()
                    val centerW = (screenWidth - scaledMinWH) / 2
                    val centerH = (screenHeight - scaledMinWH) / 2
                    // Crosshair A is always the live camera reticle.
                    val x = if (groundCameraCommandAtCenter) {
                        screenWidth / 2F
                    } else {
                        p.x.toFloat()
                    }
                    val y = if (groundCameraCommandAtCenter) {
                        screenHeight / 2F
                    } else {
                        p.y.toFloat()
                    }

                    val commandPointVisible = groundCameraCommandAtCenter ||
                            pos.canBeSeen()
                    if (crosshairPath == "@VehicleDynamicCross" && commandPointVisible) {
                        RenderHelper.blit(
                            poseStack,
                            texture,
                            x - scaledMinWH / 2,
                            y - scaledMinWH / 2,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                        renderKillIndicatorDynamic(
                            guiGraphics,
                            x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                            y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                        )
                        val fixedTexture: ResourceLocation? = CROSSHAIR_MAP["@VehicleFixedPoint"]
                        RenderHelper.blit(
                            poseStack,
                            fixedTexture,
                            centerW,
                            centerH,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                    } else if ((crosshairPath == "@AirCraftCommon" || crosshairPath == "@VehicleLaserCannon" || crosshairPath == "@VehicleCommonGunDynamic") && commandPointVisible) {
                        RenderHelper.blit(
                            poseStack,
                            texture,
                            x - scaledMinWH / 2,
                            y - scaledMinWH / 2,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                        renderKillIndicatorDynamic(
                            guiGraphics,
                            x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                            y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                        )
                    } else if (crosshairPath == "@VehicleCnHpjZooming") {
                        val dynamicTexture: ResourceLocation? = CROSSHAIR_MAP.get("@VehicleDynamicCross")
                        RenderHelper.blit(
                            poseStack,
                            dynamicTexture,
                            x - scaledMinWH / 2,
                            y - scaledMinWH / 2,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                        renderKillIndicatorDynamic(
                            guiGraphics,
                            x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                            y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                        )
                    } else if (crosshairPath == "@VehicleCommonCannonZooming") {
                        val fovAdjust = 60f / Minecraft.getInstance().options.fov().get()
                        val f = Math.min(screenWidth, screenHeight).toFloat()
                        val f1 = Math.min(screenWidth.toFloat() / f, screenHeight.toFloat() / f) * fovAdjust
                        val i = Mth.floor(f * f1)
                        val j = Mth.floor(f * f1)
                        val k = Mth.floor(x - i / 2F)
                        val l = Mth.floor(y - j / 2F)
                        RenderHelper.preciseBlit(
                            guiGraphics,
                            texture,
                            k.toFloat(),
                            l.toFloat(),
                            0f,
                            0f,
                            i.toFloat(),
                            j.toFloat(),
                            i.toFloat(),
                            j.toFloat()
                        )
                        renderKillIndicator(guiGraphics, screenWidth.toFloat(), screenHeight.toFloat())
                    } else if (crosshairPath == "@VehicleCommonSeekMissile" && data.get(GunProp.SEEK_WEAPON_INFO) != null && data.get(
                            GunProp.SEEK_WEAPON_INFO
                        )?.onlyLockBlock ?: false
                    ) {
                        var vec3 = ClientEventHandler.seekingPosVehicle
                        if (ClientEventHandler.seekingTimeVehicle > 0) {
                            vec3 = ClientEventHandler.lockingPosVehicle
                        }
                        if (vec3 != null) {
                            val string = vec3.toFormattedString()
                            val width = Minecraft.getInstance().font.width(string)
                            RenderHelper.blit(
                                poseStack,
                                texture,
                                centerW,
                                centerH,
                                0f,
                                0f,
                                scaledMinWH,
                                scaledMinWH,
                                scaledMinWH,
                                scaledMinWH,
                                color
                            )
                            guiGraphics.drawString(
                                Minecraft.getInstance().font,
                                string,
                                screenWidth.toFloat() / 2 - width.toFloat() / 2,
                                screenHeight.toFloat() - 73,
                                color,
                                false
                            )
                        }
                    } else {
                        RenderHelper.blit(
                            poseStack,
                            texture,
                            x - scaledMinWH / 2,
                            y - scaledMinWH / 2,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                        renderKillIndicator(guiGraphics, screenWidth.toFloat(), screenHeight.toFloat())
                    }
                }
            }

            poseStack.popPose()
        } else if (hasBaseCrosshair && Minecraft.getInstance().options.cameraType == CameraType.THIRD_PERSON_BACK && !ClientEventHandler.zoomVehicle) {
            // 渲染第三人称
            if (pos.canBeSeen() && !((entity.vehicleType == VehicleType.AIRPLANE || entity.vehicleType == VehicleType.HELICOPTER) && player === entity.getFirstPassenger())) {
                val x = p.x.toFloat()
                val y = p.y.toFloat()

                RenderHelper.preciseBlit(
                    guiGraphics,
                    CROSSHAIR_THIRD_CAMERA,
                    x - 12,
                    y - 12,
                    0f,
                    0f,
                    24f,
                    24f,
                    24f,
                    24f
                )
                renderKillIndicatorDynamic(
                    guiGraphics,
                    x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                    y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                )

                poseStack.pushPose()

                poseStack.translate(x, y, 0f)
                poseStack.scale(0.75f, 0.75f, 1f)

                renderWeaponInfoThird(guiGraphics, entity, player, data, mc.font)

                if (player === entity.getFirstPassenger()) {
                    if (entity.hasDecoy()) {
                        if (entity.decoyReady) {
                            guiGraphics.drawString(
                                Minecraft.getInstance().font,
                                Component.translatable("tips.superbwarfare.smoke.ready").append(
                                    Component.literal(
                                        " [" + ModKeyMappings.RELEASE_DECOY.key.displayName.string + "]"
                                    )
                                ),
                                30,
                                1,
                                -1,
                                false
                            )
                        } else {
                            guiGraphics.drawString(
                                Minecraft.getInstance().font,
                                Component.translatable("tips.superbwarfare.smoke.reloading"),
                                30,
                                1,
                                0xFF0000,
                                false
                            )
                        }
                    }
                }

                poseStack.popPose()
            }
        }

        poseStack.popPose()
        renderCcipIndicator(entity)
        if (hasAimStateMarker) renderAimStateMarker(entity, index)
    }


    private fun resetScale() {
        scopeScale = 0.7f
    }

    private fun RenderContext.renderAimStateMarker(
        entity: VehicleEntity,
        seatIndex: Int,
    ) {
        val provider = entity as? VehicleAimReticleProfileProvider ?: return
        val weaponIndex = entity.getPrimaryWeaponIndex(seatIndex)
        val gunData = entity.getGunData(seatIndex, weaponIndex) ?: return
        if (VehicleWeaponGuidance.isAtgm(gunData)) return
        val profile = provider.getVehicleAimReticleProfile(seatIndex, weaponIndex) ?: return
        // Ground Crosshair B consumes only the epoch-paired authoritative presentation tuple.
        // Flight vehicles intentionally have no aim-presentation timeline, so they may use one
        // current selected physical muzzle sample. That fallback has no coherent lock truth and
        // therefore remains transit red.
        val frame = entity.resolveAimPresentationFrame(player, partialTick)
        val muzzle = (frame?.muzzle ?: if (entity.resolveVehicleFlightStrategy() != null) {
            entity.resolveMuzzleFrame(player, partialTick)
        } else {
            return
        }) ?: return
        val origin = muzzle.position
        val direction = muzzle.direction
        if (direction.lengthSqr() < 1.0E-6 ||
            !direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite()
        ) return
        val actualDirection = direction.normalize()
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val cameraOrigin = camera.position
        val cameraLook = Vec3(camera.lookVector)
        if (!finiteDirection(cameraLook)) return
        val cameraDirection = cameraLook.normalize()
        val zero = VehicleGeometricZeroDistanceClient.activeFcsState(player)
        val zeroRange = zero?.takeIf {
            it.status == com.atsuishio.superbwarfare.network.VehicleGeometricZeroWireStatus.SOLUTION
        }?.measuredRangeBlocks
        val range = zeroRange ?: VehicleGeometricZeroDistanceClient.activeDistance(player)?.toDouble()
        val impact = zeroRange?.let { distance ->
            val capture = com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleShotPredictionService
                .captureClientNominalSnapshotIfFiredNow(entity, player, partialTick)
            capture.snapshot?.takeIf { it.selectedWeaponIndex == weaponIndex }?.let { shot ->
                com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleRangeBallistics.atRangePlane(
                    shot, cameraOrigin, cameraDirection, distance)
            }
        }
        val aimPoint = range?.let { distance ->
            val cameraDepth = distance.toDouble() - origin.subtract(cameraOrigin).dot(cameraDirection)
            val rayPlaneCosine = actualDirection.dot(cameraDirection)
            if (!cameraDepth.isFinite() || !rayPlaneCosine.isFinite() ||
                abs(rayPlaneCosine) <= ZERO_RAY_PARALLEL_EPSILON
            ) return
            val rayParameter = cameraDepth / rayPlaneCosine
            if (!rayParameter.isFinite() || rayParameter <= 0.0) return
            origin.add(actualDirection.scale(rayParameter))
        } ?: origin.add(actualDirection.scale(AIM_STATE_MARKER_DISTANCE))
        if (aimPoint.subtract(camera.position).dot(cameraLook.normalize()) <= 0.0) return
        val projected = aimPoint.worldToScreen()
        val projectedX = projected.x
        val projectedY = projected.y
        if (!projectedX.isFinite() || !projectedY.isFinite()) return

        // Color is diagnostic only and comes from the same epoch-paired actual-aim sample as
        // the displayed muzzle.
        val aimProfile = entity.resolveVehicleAimProfile(seatIndex, weaponIndex)
        val commandDirection = player.getViewVector(partialTick)
            ?.takeIf(::finiteDirection)?.normalize()
        val directionAligned = commandDirection != null && aimProfile != null &&
                java.lang.Math.toDegrees(acos(actualDirection.dot(commandDirection).coerceIn(-1.0, 1.0))) <=
                aimProfile.lockToleranceDegrees
        val impactProjection = impact?.takeIf { it.subtract(cameraOrigin).dot(cameraDirection) > 0 }
            ?.worldToScreen()?.takeIf { it.x.isFinite() && it.y.isFinite() }
        val centerResidual = hypot(
            (impactProjection?.x ?: projectedX) - screenWidth / 2.0,
            (impactProjection?.y ?: projectedY) - screenHeight / 2.0,
        )
        val locked = frame != null && !frame.continuityReprojected && frame.aim.lockedDiagnostic &&
                (if (zeroRange != null) impactProjection != null else directionAligned) &&
                centerResidual <= profile.centerSnapPixels

        val margin = profile.clampMarginPixels
        val x = projectedX.roundToInt().coerceIn(margin, (screenWidth - margin - 1).coerceAtLeast(margin))
        val y = projectedY.roundToInt().coerceIn(margin, (screenHeight - margin - 1).coerceAtLeast(margin))
        if (impactProjection != null && projectedX in 0.0..screenWidth.toDouble() &&
            projectedY in 0.0..screenHeight.toDouble()) {
            val color = if (locked) 0xE080FF80.toInt() else 0xE0FFB860.toInt()
            guiGraphics.enableScissor(0, 0, screenWidth, screenHeight)
            drawFineLine(projectedX, projectedY, impactProjection.x, impactProjection.y, color)
            drawFineCircle(impactProjection.x, impactProjection.y, 1.7, color, true)
            guiGraphics.disableScissor()
        }
        drawAimStateMarker(x, y, locked, entity)
    }

    private fun RenderContext.renderCcipIndicator(entity: VehicleEntity) {
        val view = VehicleCcipPresentation.activeView(player, partialTick) ?: run {
            ccipScreenSmoother.reset()
            return
        }
        val result = view.result
        if (result.status != NominalShotStatus.IMPACT &&
            result.status != NominalShotStatus.WATER_SURFACE_IMPACT
        ) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.NOT_EVALUATED)
            return
        }
        val impact = VehicleCcipPresentation.presentationPoint()
        if (impact == null || !impact.x.isFinite() || !impact.y.isFinite() || !impact.z.isFinite()) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.NON_FINITE)
            return
        }
        val toPoint = impact.subtract(cameraPos)
        val look = Vec3(camera.lookVector)
        if (look.lengthSqr() <= 1.0E-8 || toPoint.dot(look.normalize()) <= 0.0) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.BEHIND_CAMERA)
            return
        }
        val projected = impact.worldToScreen()
        if (!projected.x.isFinite() || !projected.y.isFinite()) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.NON_FINITE)
            return
        }
        if (projected.x < 0.0 || projected.x >= screenWidth || projected.y < 0.0 || projected.y >= screenHeight) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.OFFSCREEN)
            return
        }
        if (projected.x < CCIP_HALF_SIZE || projected.x >= screenWidth - CCIP_HALF_SIZE ||
            projected.y < CCIP_HALF_SIZE || projected.y >= screenHeight - CCIP_HALF_SIZE
        ) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.MARGIN_CLIPPED)
            return
        }
        // Only the final GUI projection is filtered. The world endpoint above remains the
        // newest completed predictor result; no trajectory, muzzle, or collision state is reused.
        if (!ccipScreenSmoother.update(
                entity.level(),
                player.uuid,
                entity.id,
                entity.uuid,
                view.seatIndex,
                view.selectedWeaponIndex,
                view.weaponName,
                result.status,
                projected.x,
                projected.y,
                (mc.deltaFrameTime.toDouble() / 20.0).coerceIn(0.0, CCIP_MAX_DT_SECONDS),
            )) {
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.NON_FINITE)
            return
        }
        val x = ccipScreenSmoother.x.roundToInt()
        val y = ccipScreenSmoother.y.roundToInt()
        if (x !in CCIP_HALF_SIZE until (screenWidth - CCIP_HALF_SIZE) ||
            y !in CCIP_HALF_SIZE until (screenHeight - CCIP_HALF_SIZE)
        ) {
            ccipScreenSmoother.reset()
            VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.MARGIN_CLIPPED)
            return
        }
        VehicleCcipPresentation.reportProjection(player, VehicleCcipPresentation.ProjectionDiagnostic.VISIBLE)
        // Editable 1:1 CCIP artwork: nominal center trajectory, first geometric block contact.
        val texture = if (entity.vehicleType in GROUND_CCIP_TYPES) CCIP_TANK_TEXTURE else CCIP_TEXTURE
        guiGraphics.blit(
            texture,
            x - CCIP_HALF_SIZE,
            y - CCIP_HALF_SIZE,
            0f,
            0f,
            CCIP_TEXTURE_SIZE,
            CCIP_TEXTURE_SIZE,
            CCIP_TEXTURE_SIZE,
            CCIP_TEXTURE_SIZE,
        )
    }

    private fun finiteDirection(direction: Vec3): Boolean =
        direction.x.isFinite() && direction.y.isFinite() && direction.z.isFinite() && direction.lengthSqr() > 1.0E-8

    /**
     * Draw the physical barrel marker from authored pixels rather than procedural rectangles.
     * The selected PNG is uploaded with an opaque white vertex color so its authored RGBA is
     * preserved exactly. Optional per-vehicle zoom artwork is discovered once per resource epoch at
     * `textures/overlay/vehicle/crosshair/<entity-path>_physical_zoom.png` and otherwise falls
     * back to the normal pointer. A `_locked` variant is likewise a complete authored image,
     * never a tint applied to the base artwork.
     */
    private fun RenderContext.drawAimStateMarker(
        x: Int,
        y: Int,
        locked: Boolean,
        entity: VehicleEntity,
    ) {
        val texture = resolvePhysicalCrosshairTexture(entity, locked)
        if (texture == CROSSHAIR_B_TEXTURE || texture.path == CROSSHAIR_B_TEXTURE.path.removeSuffix(".png") + "_locked.png") {
            drawFineCircle(x.toDouble(), y.toDouble(), 3.2,
                if (locked) 0xF080FF80.toInt() else 0xF0FFB860.toInt(), false)
            return
        }
        RenderHelper.blit(
            guiGraphics.pose(),
            texture,
            x.toFloat() - CROSSHAIR_B_HALF_SIZE,
            y.toFloat() - CROSSHAIR_B_HALF_SIZE,
            0f,
            0f,
            CROSSHAIR_B_TEXTURE_SIZE.toFloat(),
            CROSSHAIR_B_TEXTURE_SIZE.toFloat(),
            CROSSHAIR_B_TEXTURE_SIZE.toFloat(),
            CROSSHAIR_B_TEXTURE_SIZE.toFloat(),
            1f,
        )
    }

    /** Quarter-GUI-pixel geometry remains crisp at different GUI scales; no enlarged pixel sprite. */
    private fun RenderContext.drawFineLine(x: Double, y: Double, endX: Double, endY: Double, color: Int) {
        val length = hypot(endX - x, endY - y)
        if (!length.isFinite() || length > 4.0 * (screenWidth + screenHeight)) return
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x, y, 0.0)
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(kotlin.math.atan2(endY - y, endX - x).toFloat()))
        pose.scale(0.25F, 0.25F, 1F)
        guiGraphics.fill(0, -1, (length * 4).roundToInt().coerceAtLeast(1), 1, color)
        pose.popPose()
    }

    private fun RenderContext.drawFineCircle(x: Double, y: Double, radius: Double, color: Int, filled: Boolean) {
        if (filled) {
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x, y, 0.0)
            pose.scale(0.25F, 0.25F, 1F)
            val r = radius * 4
            for (row in -r.toInt()..r.toInt()) {
                val half = kotlin.math.sqrt((r * r - row * row).coerceAtLeast(0.0)).roundToInt()
                guiGraphics.fill(-half, row, half + 1, row + 1, color)
            }
            pose.popPose()
        } else for (i in 0 until 40) {
            val a = i * Math.PI / 20
            val b = (i + 1) * Math.PI / 20
            drawFineLine(x + kotlin.math.cos(a) * radius, y + kotlin.math.sin(a) * radius,
                x + kotlin.math.cos(b) * radius, y + kotlin.math.sin(b) * radius, color)
        }
    }

    private fun resolvePhysicalCrosshairTexture(entity: VehicleEntity, locked: Boolean): ResourceLocation {
        val manager = Minecraft.getInstance().resourceManager
        if (manager !== physicalCrosshairResourceManager) {
            physicalCrosshairResourceManager = manager
            physicalCrosshairResourceCache.clear()
        }

        val typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        val zoomTexture = if (ClientEventHandler.zoomVehicle) {
            ResourceLocation(
                typeId.namespace,
                "textures/overlay/vehicle/crosshair/${typeId.path}_physical_zoom.png",
            ).takeIf { hasPhysicalCrosshairResource(manager, it) }
        } else {
            null
        }
        val base = zoomTexture ?: CROSSHAIR_B_TEXTURE
        if (!locked) return base

        val lockedTexture = ResourceLocation(
            base.namespace,
            base.path.removeSuffix(".png") + "_locked.png",
        )
        return if (hasPhysicalCrosshairResource(manager, lockedTexture)) lockedTexture else base
    }

    private fun hasPhysicalCrosshairResource(manager: ResourceManager, location: ResourceLocation): Boolean {
        val key = location.toString()
        physicalCrosshairResourceCache[key]?.let { return it }
        if (physicalCrosshairResourceCache.size >= MAX_PHYSICAL_CROSSHAIR_RESOURCES) {
            // The entity registry is finite; discard one oldest entry rather than allowing a
            // malformed/foreign registry to grow an unbounded HUD cache.
            physicalCrosshairResourceCache.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
        val present = try {
            manager.getResource(location).isPresent
        } catch (_: RuntimeException) {
            false
        }
        physicalCrosshairResourceCache[key] = present
        return present
    }

    private const val AIM_STATE_MARKER_DISTANCE = 160.0
    private const val ZERO_RAY_PARALLEL_EPSILON = 1.0E-5
    // Authored 32x32 pointer artwork; render dimensions and UVs are intentionally 1:1.
    private val CROSSHAIR_B_TEXTURE = loc("textures/overlay/vehicle/cannon/roll_ind_white.png")
    private const val CROSSHAIR_B_TEXTURE_SIZE = 32
    private const val CROSSHAIR_B_HALF_SIZE = CROSSHAIR_B_TEXTURE_SIZE / 2
    private const val MAX_PHYSICAL_CROSSHAIR_RESOURCES = 128
    private var physicalCrosshairResourceManager: ResourceManager? = null
    private val physicalCrosshairResourceCache = HashMap<String, Boolean>()
    private val CCIP_TEXTURE = loc("textures/overlay/vehicle/crosshair/ccip.png")
    private val CCIP_TANK_TEXTURE = loc("textures/overlay/vehicle/crosshair/ccip_tank.png")
    private val GROUND_CCIP_TYPES = setOf(
        VehicleType.TANK,
        VehicleType.APC,
        VehicleType.AA,
        VehicleType.CAR,
        VehicleType.ARTILLERY,
        VehicleType.DEFENSE,
    )
    private const val CCIP_TEXTURE_SIZE = 16
    private const val CCIP_HALF_SIZE = CCIP_TEXTURE_SIZE / 2
    private const val CCIP_RESPONSE_RATE = 18.0
    private const val CCIP_MAX_DT_SECONDS = 0.25
    private const val CCIP_LARGE_JUMP_PIXELS = 96.0
    private const val CCIP_SNAP_EPSILON_PIXELS = 0.25

    /** Render-only screen-space filter; it never stores or interpolates a world endpoint. */
    private class CcipScreenSmoother {
        private var levelReference: Any? = null
        private var playerUuid: UUID? = null
        private var vehicleId = 0
        private var vehicleUuid: UUID? = null
        private var seatIndex = -1
        private var selectedWeaponIndex = -1
        private var weaponName: String? = null
        private var status: NominalShotStatus? = null
        private var initialized = false

        var x = 0.0
            private set
        var y = 0.0
            private set

        fun reset() {
            levelReference = null
            playerUuid = null
            vehicleUuid = null
            weaponName = null
            status = null
            seatIndex = -1
            selectedWeaponIndex = -1
            vehicleId = 0
            initialized = false
            x = 0.0
            y = 0.0
        }

        fun update(
            level: Any,
            nextPlayerUuid: UUID,
            nextVehicleId: Int,
            nextVehicleUuid: UUID,
            nextSeatIndex: Int,
            nextSelectedWeaponIndex: Int,
            nextWeaponName: String,
            nextStatus: NominalShotStatus,
            targetX: Double,
            targetY: Double,
            deltaSeconds: Double,
        ): Boolean {
            if (!targetX.isFinite() || !targetY.isFinite()) {
                reset()
                return false
            }
            val sameContext = initialized && levelReference === level &&
                playerUuid == nextPlayerUuid && vehicleId == nextVehicleId &&
                vehicleUuid == nextVehicleUuid && seatIndex == nextSeatIndex &&
                selectedWeaponIndex == nextSelectedWeaponIndex && weaponName == nextWeaponName &&
                status == nextStatus
            if (!sameContext) {
                levelReference = level
                playerUuid = nextPlayerUuid
                vehicleId = nextVehicleId
                vehicleUuid = nextVehicleUuid
                seatIndex = nextSeatIndex
                selectedWeaponIndex = nextSelectedWeaponIndex
                weaponName = nextWeaponName
                status = nextStatus
                x = targetX
                y = targetY
                initialized = true
                return true
            }

            val distance = hypot(targetX - x, targetY - y)
            if (!distance.isFinite() || distance >= CCIP_LARGE_JUMP_PIXELS) {
                x = targetX
                y = targetY
                return true
            }
            val alpha = (1.0 - exp(-CCIP_RESPONSE_RATE * deltaSeconds.coerceIn(0.0, CCIP_MAX_DT_SECONDS)))
                .coerceIn(0.0, 1.0)
            x += (targetX - x) * alpha
            y += (targetY - y) * alpha
            if (hypot(targetX - x, targetY - y) <= CCIP_SNAP_EPSILON_PIXELS) {
                x = targetX
                y = targetY
            }
            return x.isFinite() && y.isFinite()
        }
    }

    private val ccipScreenSmoother = CcipScreenSmoother()
}
