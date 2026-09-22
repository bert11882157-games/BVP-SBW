package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.overlay.VehicleMainWeaponHudOverlay.renderEnergyInfo
import com.atsuishio.superbwarfare.client.overlay.VehicleHudLayout
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.MathTool.getGradientColor
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.gui.overlay.ForgeGui
import org.joml.Math
import kotlin.math.roundToInt

@OnlyIn(Dist.CLIENT)
object LandVehicleHud {
    const val ID: String = "@Land"

    private val COMPASS = loc("textures/overlay/vehicle/base/compass.png")
    private val ROLL_IND = loc("textures/overlay/vehicle/helicopter/roll_ind.png")

    // 地面载具车身显示
    private val FRAME = loc("textures/overlay/vehicle/land/tv_frame.png")
    private val LINE = loc("textures/overlay/vehicle/land/line.png")
    private val BARREL = loc("textures/overlay/vehicle/land/line.png")
    private val BODY = loc("textures/overlay/vehicle/land/body.png")
    private val LEFT_WHEEL = loc("textures/overlay/vehicle/land/left_wheel.png")
    private val RIGHT_WHEEL = loc("textures/overlay/vehicle/land/right_wheel.png")
    private val ENGINE = loc("textures/overlay/vehicle/land/engine.png")

    // The authored orientation resources are 64x64 (the legacy SBW glyph was a 32-unit
    // destination).  Always sample the complete image explicitly so a resized destination can
    // never wrap UVs and produce the former tiled/lattice appearance.
    private const val ORIENTATION_TEXTURE_SIZE = 64f
    private const val ORIENTATION_VERTICAL_OFFSET_LINES = 0.65f

    var lerpRecoil: Float = 0f

    fun render(
        vehicle: VehicleEntity,
        player: Player,
        gui: ForgeGui,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val mc = gui.getMinecraft()

        if (vehicle.getSeatIndex(player) != vehicle.computed().turretControllerIndex) return

        val poseStack = guiGraphics.pose()

        val color = vehicle.hudColor

        poseStack.pushPose()

        val recoil = Mth.lerp(partialTick, vehicle.recoilShakeO.toFloat(), vehicle.recoilShake.toFloat())
        lerpRecoil = Mth.lerp(0.1f * partialTick, lerpRecoil, recoil * (2 * (Math.random() - 0.5f)).toFloat())
        poseStack.translate(
            lerpRecoil * 6 + screenWidth * 0.025f * recoil,
            recoil * 3 + screenHeight * 0.025f * recoil,
            0f
        )
        poseStack.scale(1 - recoil * 0.05f, 1 - recoil * 0.05f, 1f)
        poseStack.rotateAround(
            Axis.ZP.rotationDegrees(-0.3f * ClientEventHandler.cameraRoll + 2.5f * lerpRecoil),
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

        if (Minecraft.getInstance().options.cameraType == CameraType.FIRST_PERSON || ClientEventHandler.zoomVehicle) {
            val addW = (screenWidth / screenHeight) * 48
            val addH = (screenWidth / screenHeight) * 27
            RenderHelper.preciseBlit(
                guiGraphics,
                FRAME,
                -addW.toFloat() / 2,
                -addH.toFloat() / 2,
                10f,
                0f,
                0f,
                (screenWidth + addW).toFloat(),
                (screenHeight + addH).toFloat(),
                (screenWidth + addW).toFloat(),
                (screenHeight + addH).toFloat()
            )
            RenderHelper.blit(
                poseStack,
                LINE,
                screenWidth / 2f - 64,
                (screenHeight - 56).toFloat(),
                0f,
                0f,
                128f,
                1f,
                128f,
                1f,
                color
            )

            // 指南针
            RenderHelper.blit(
                poseStack,
                COMPASS,
                screenWidth.toFloat() / 2 - 128,
                10f,
                128 + (64f / 45 * player.yRot),
                0f,
                256f,
                16f,
                512f,
                16f,
                color
            )
            RenderHelper.blit(poseStack, ROLL_IND, screenWidth / 2f - 8, 30f, 0f, 0f, 16f, 16f, 16f, 16f, color)

            val layout = VehicleHudLayout.scaled(screenWidth, screenHeight, mc.font.lineHeight)
            val orientationZone = layout.element("orientation")
            val decoyZone = layout.element("decoy")
            val indicatorSize = minOf(
                orientationZone.width,
                orientationZone.height,
                (minOf(orientationZone.width, orientationZone.height) * 0.30f).toInt(),
            ).coerceAtLeast(1)
            val indicatorCenterX = orientationZone.centerX
            // Keep the compact glyph immediately below the compass in the authored upper-center
            // cell, with a small line-height offset so it does not crowd the compass.
            val indicatorCenterY = (
                orientationZone.top + indicatorSize / 2 +
                    (orientationZone.lineHeight.toFloat() * ORIENTATION_VERTICAL_OFFSET_LINES).roundToInt()
            ).coerceAtMost(orientationZone.bottom - indicatorSize / 2)
            val indicatorLeft = indicatorCenterX - indicatorSize / 2
            val indicatorTop = indicatorCenterY - indicatorSize / 2

            // Reuse the original SBW tank indicator relationship: BARREL is the fixed forward
            // reference and the complete body/wheel/engine stack rotates by turret bearing.
            // This is presentation only; no aim frame is read here.
            val turretHeading = Mth.rotLerp(partialTick, vehicle.turretYRotO, vehicle.turretYRot)
                .takeIf(Float::isFinite) ?: 0f
            val lineWidth = (indicatorSize / 32f).coerceAtLeast(1f)
            val lineHeight = (indicatorSize / 2f).coerceAtLeast(1f)
            val turretHeal = (100 - (100 * vehicle.turretHealth / vehicle.getTurretMaxHealth())).toInt()

            // Fixed forward reference line from the original SBW orientation indicator.
            RenderHelper.blit(
                poseStack,
                BARREL,
                indicatorCenterX.toFloat() - lineWidth / 2f,
                indicatorCenterY.toFloat() - lineHeight,
                lineWidth,
                lineHeight,
                0f,
                0f,
                1f,
                1f,
                1f,
                1f,
                getGradientColor(color, 0xFF0000, turretHeal, 2)
            )

            poseStack.pushPose()
            poseStack.rotateAround(
                Axis.ZP.rotationDegrees(turretHeading),
                indicatorCenterX.toFloat(), indicatorCenterY.toFloat(), 0f
            )
            val bodyHeal = (100 - (100 * vehicle.health / vehicle.getMaxHealth())).toInt()
            RenderHelper.blit(
                poseStack,
                BODY,
                indicatorLeft.toFloat(),
                indicatorTop.toFloat(),
                indicatorSize.toFloat(),
                indicatorSize.toFloat(),
                0f,
                0f,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                getGradientColor(color, 0xFF0000, bodyHeal, 2)
            )
            val leftWheelHeal = (100 - (100 * vehicle.leftWheelHealth / vehicle.getWheelMaxHealth())).toInt()
            RenderHelper.blit(
                poseStack,
                LEFT_WHEEL,
                indicatorLeft.toFloat(),
                indicatorTop.toFloat(),
                indicatorSize.toFloat(),
                indicatorSize.toFloat(),
                0f,
                0f,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                getGradientColor(color, 0xFF0000, leftWheelHeal, 2)
            )
            val rightWheelHeal = (100 - (100 * vehicle.rightWheelHealth / vehicle.getWheelMaxHealth())).toInt()
            RenderHelper.blit(
                poseStack,
                RIGHT_WHEEL,
                indicatorLeft.toFloat(),
                indicatorTop.toFloat(),
                indicatorSize.toFloat(),
                indicatorSize.toFloat(),
                0f,
                0f,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                getGradientColor(color, 0xFF0000, rightWheelHeal, 2)
            )
            val engineHeal = (100 - (100 * vehicle.mainEngineHealth / vehicle.getEngineMaxHealth())).toInt()
            RenderHelper.blit(
                poseStack,
                ENGINE,
                indicatorLeft.toFloat(),
                indicatorTop.toFloat(),
                indicatorSize.toFloat(),
                indicatorSize.toFloat(),
                0f,
                0f,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                ORIENTATION_TEXTURE_SIZE,
                getGradientColor(color, 0xFF0000, engineHeal, 2)
            )
            poseStack.popPose()

            // 低电量警告
            renderEnergyInfo(vehicle, guiGraphics, screenWidth, screenHeight, mc.font)

            // 诱饵
            if (vehicle.hasDecoy() && player === vehicle.getFirstPassenger()) {
                if (vehicle.decoyReady) {
                    val prompt = Component.translatable("tips.superbwarfare.smoke.ready").append(
                        Component.literal(" [" + ModKeyMappings.RELEASE_DECOY.key.displayName.string + "]")
                    ).string
                    drawBoundedText(guiGraphics, mc.font, prompt, decoyZone, decoyZone.centerX,
                        decoyZone.centerY, color, TextAlignment.CENTER, false)
                } else {
                    val prompt = Component.translatable("tips.superbwarfare.smoke.reloading").string
                    drawBoundedText(guiGraphics, mc.font, prompt, decoyZone, decoyZone.centerX,
                        decoyZone.centerY, 0xFF0000, TextAlignment.CENTER, false)
                }
            }

        }
        poseStack.popPose()
    }

    private enum class TextAlignment { LEFT, CENTER, RIGHT }

    private fun drawBoundedText(
        guiGraphics: GuiGraphics,
        font: net.minecraft.client.gui.Font,
        text: String,
        zone: VehicleHudLayout.ScaledZone,
        anchorX: Int,
        y: Int,
        color: Int,
        alignment: TextAlignment,
        shadow: Boolean = true,
    ) {
        val boundedWidth = zone.maxTextWidth.coerceAtLeast(1)
        val shown = if (font.width(text) <= boundedWidth) text else {
            val suffix = "…"
            val prefixWidth = (boundedWidth - font.width(suffix)).coerceAtLeast(0)
            font.plainSubstrByWidth(text, prefixWidth) + suffix
        }
        val shownWidth = font.width(shown)
        val drawX = when (alignment) {
            TextAlignment.LEFT -> 0
            TextAlignment.CENTER -> -shownWidth / 2
            TextAlignment.RIGHT -> -shownWidth
        }
        val boundedY = y.coerceIn(zone.top, maxOf(zone.top, zone.bottom - font.lineHeight))
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(anchorX.toFloat(), boundedY.toFloat(), 0f)
        guiGraphics.drawString(font, shown, drawX, 0, color, shadow)
        pose.popPose()
    }
}
