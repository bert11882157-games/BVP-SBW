package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.FixedWingPilotIntentClient
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicatorDynamic
import com.atsuishio.superbwarfare.client.overlay.VehicleMainWeaponHudOverlay
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.event.ClientMouseHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.*
import com.atsuishio.superbwarfare.tools.FormatTool.format0D
import com.atsuishio.superbwarfare.tools.MathTool.getGradientColor
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.gui.overlay.ForgeGui
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.joml.Math

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(Dist.CLIENT)
object AircraftHud {
    // Steering-circle ticks toward the nose (GUI px, ring radius 6.5): three bars across the direction, the first
    // just outside the ring, spaced about a third of the ring's diameter apart.
    private const val JOYSTICK_TICKS = 3
    private const val TICK_GAP = 1.4f
    private const val TICK_SPACING = 4.6f
    private const val TICK_HALF_LENGTH = 1.0f
    private const val TICK_HALF_WIDTH = 0.3f
    private const val TICK_ALPHA = 200
    private const val NOSE_CLEARANCE = 4f
    const val ID: String = "@Aircraft"

    private var lerpVy = 1f
    private var lerpG = 1f
    private var diffY = 0f
    private var diffX = 0f

    var bombHitPosX: Double = 0.0
    var bombHitPosY: Double = 0.0

    private val BOMB_SCOPE = loc("textures/overlay/vehicle/aircraft/bomb_scope.png")
    private val BOMB_SCOPE_PITCH = loc("textures/overlay/vehicle/aircraft/bomb_scope_pitch.png")
    private val HUD_BASE_MISSILE = loc("textures/overlay/vehicle/aircraft/hud_base_missile.png")
    private val HUD_BASE = loc("textures/overlay/vehicle/aircraft/hud_base.png")
    val HUD_LINE = loc("textures/overlay/vehicle/aircraft/hud_line.png")
    val HUD_LINE_3P = loc("textures/overlay/vehicle/aircraft/hud_line_3p.png")
    val ROLL_HUD_3P = loc("textures/overlay/vehicle/aircraft/roll_hud_3p.png")
    private val HUD_IND = loc("textures/overlay/vehicle/aircraft/hud_ind.png")
    private val HUD_BOMB = loc("textures/overlay/vehicle/aircraft/bomb.png")
    private val HUD_BASE2 = loc("textures/overlay/vehicle/aircraft/hud_base2.png")
    private val COMPASS_IND = loc("textures/overlay/vehicle/aircraft/compass_ind.png")
    private val HELICOPTER_ROLL_IND = loc("textures/overlay/vehicle/helicopter/roll_ind.png")
    private val HELICOPTER_SPEED_FRAME = loc("textures/overlay/vehicle/helicopter/speed_frame.png")
    val POWER_RULER = loc("textures/overlay/vehicle/aircraft/power_ruler.png")

    private val COMPASS = loc("textures/overlay/vehicle/base/compass.png")
    private val BOMB_RING = loc("textures/overlay/crosshair/rex_circle.png")

    private var mouseX = 0f
    private var mouseY = 0f
    private var lerpPower = 0f

    private var dis = 512.0

    @SubscribeEvent
    fun onAircraftHudClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) return
        val player = localPlayer ?: return
        val vehicle = player.vehicle
        if (vehicle !is VehicleEntity) return
        if (vehicle.computed().hudType != ID) return
        // The minimal aircraft cue follows the body, so it needs no muzzle raycast.
        if (vehicle.isFixedWingFlightVehicle() ||
            vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE) return
        if (vehicle.getGunData(player) == null) return

        val shootPos = vehicle.getShootPosForHud(player, 1f)

        val result = player.level().clip(
            ClipContext(
                shootPos, shootPos.add(vehicle.getShootDirectionForHud(player, 1f).scale(512.0)),
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player
            )
        )
        val hitPos = result.location

        dis = shootPos.distanceTo(hitPos)

        val lookingEntity = vehicle.getPlayerLookAtEntityOnVehicle(player, 512.0, 1f)

        if (lookingEntity != null) {
            dis = shootPos.distanceTo(lookingEntity.position())
        }
    }

    fun render(
        vehicle: VehicleEntity,
        player: Player,
        gui: ForgeGui,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (player !== vehicle.getFirstPassenger()) return
        val camera = mc.gameRenderer.mainCamera
        val cameraPos = camera.position
        val poseStack = guiGraphics.pose()
        val fixedWing = vehicle.isFixedWingFlightVehicle()
        val gunData = vehicle.getGunData(player)
        if (gunData == null && !fixedWing) return
        val fixedWingStrategy = if (fixedWing) {
            vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy
        } else null
        val flightPresentation = if (fixedWingStrategy != null && partialTick.isFinite()) {
            FixedWingHudMetrics.acceptedSnapshot(vehicle.getVehicleFlightPresentationSnapshot(partialTick))
        } else null
        // One wrapped attitude tuple drives all HUD layers and their body projection.
        val bodyYaw = flightPresentation?.bodyYaw
            ?: FixedWingHudMetrics.angle(vehicle.yRotO, vehicle.yRot, partialTick) ?: return
        val bodyPitch = flightPresentation?.bodyPitch
            ?: FixedWingHudMetrics.angle(vehicle.xRotO, vehicle.xRot, partialTick) ?: return
        val bodyRoll = flightPresentation?.bodyRoll
            ?: FixedWingHudMetrics.angle(vehicle.prevRoll, vehicle.roll, partialTick) ?: return
        if (!bodyYaw.isFinite() || !bodyPitch.isFinite() || !bodyRoll.isFinite()) return

        if (fixedWing || vehicle.vehicleType ==
            com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE) {
            // The forward cue follows body direction in both camera modes, independently of
            // the muzzle, aim command and flight-path vector. Hide it when looking away.
            val renderedForward = vehicle.getVehicleTransform(partialTick).transformDirection(org.joml.Vector3d(0.0, 0.0, 1.0)).normalize()
            val forward = cameraPos.add(Vec3(renderedForward.x, renderedForward.y, renderedForward.z).scale(512.0))
                .worldToScreen()
            if (FixedWingHudMetrics.visibleProjection(forward, screenWidth, screenHeight)) {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F)
                RenderHelper.blit(poseStack, HelicopterHud.RING,
                    forward.x.toFloat() - 2F, forward.y.toFloat() - 2F,
                    0F, 0F, 4F, 4F, 4F, 4F, vehicle.hudColor)
            }
            if (fixedWing) renderFlightAim(vehicle, player, guiGraphics, flightPresentation, screenWidth, screenHeight, partialTick)
            com.atsuishio.superbwarfare.client.overlay.VehicleSystemsHud.aircraft(
                guiGraphics, vehicle, player, screenWidth,
                if (fixedWing) flightPresentation?.throttle?.toDouble() else vehicle.power.toDouble(),
                if (fixedWing) FixedWingHudMetrics.speedKmh(flightPresentation) else vehicle.absoluteSpeed * 72)
            com.atsuishio.superbwarfare.client.overlay.VehicleSystemsHud.ground(
                guiGraphics, vehicle, player, screenWidth, screenHeight, true)
            return
        }

        poseStack.pushPose()

        val bomb = gunData?.get(GunProp.CROSSHAIR) == "@AirBomb"

        val color = vehicle.hudColor
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

        lerpVy = Mth.lerp((0.021f * partialTick).toDouble(), lerpVy.toDouble(), vehicle.deltaMovement.y() * 20)
            .toFloat()
        diffY = if (fixedWing) 0F else Mth.lerp(partialTick.toDouble(), diffY.toDouble(), ClientMouseHandler.lerpSpeedX).toFloat()
        diffX = if (fixedWing) 0F else Mth.lerp(partialTick.toDouble(), diffX.toDouble(), ClientMouseHandler.lerpSpeedY).toFloat()
        val speed = if (fixedWing) FixedWingHudMetrics.speedKmh(flightPresentation) ?: 0.0
            else vehicle.absoluteSpeed * 72

        val pos = cameraPos.add(Vec3.directionFromRotation(bodyPitch, bodyYaw).scale(512.0))
        // The unarmed attitude ladder is anchored to the body, without synthesizing a muzzle/gun.
        var posCross = if (gunData != null) vehicle.getShootPosForHud(player, partialTick)
            .add(vehicle.getShootDirectionForHud(player, partialTick).scale(dis)) else pos

        if (bomb) {
            val bombHitPosO = ClientEventHandler.bombHitPosO
            val bombHitPos = ClientEventHandler.bombHitPos
            val bombHitPosX = Mth.lerp(partialTick.toDouble(), bombHitPosO.x, bombHitPos.x)
            val bombHitPosY = Mth.lerp(partialTick.toDouble(), bombHitPosO.y, bombHitPos.y)
            val bombHitPosZ = Mth.lerp(partialTick.toDouble(), bombHitPosO.z, bombHitPos.z)
            posCross = Vec3(bombHitPosX, bombHitPosY, bombHitPosZ)
        }

        val p = pos.worldToScreen()
        val pCross = posCross.worldToScreen()
        val bodyVisible = FixedWingHudMetrics.visibleProjection(p, screenWidth, screenHeight)
        val crossVisible = FixedWingHudMetrics.visibleProjection(pCross, screenWidth, screenHeight)

        // 投弹准星
        if (bomb && ClientEventHandler.zoomVehicle && mc.options.cameraType == CameraType.FIRST_PERSON) {
            if (crossVisible) {
                val f = Math.min(screenWidth, screenHeight).toFloat()
                val f1 = Math.min(screenWidth.toFloat() / f, screenHeight.toFloat() / f)
                val i = Mth.floor(f * f1)
                val j = Mth.floor(f * f1)

                val x = screenWidth.toFloat() / 2
                val y = screenHeight.toFloat() / 2

                poseStack.pushPose()
                poseStack.translate(x, y, 0f)
                val component = vehicle.thirdPersonAmmoComponent(gunData, player)
                guiGraphics.drawString(mc.font, component, 25, -11, 1, false)
                poseStack.popPose()

                RenderHelper.preciseBlit(
                    guiGraphics,
                    BOMB_SCOPE,
                    x - 1.5f * i,
                    y - 1.5f * j,
                    0f,
                    0f,
                    (3 * i).toFloat(),
                    (3 * j).toFloat(),
                    (3 * i).toFloat(),
                    (3 * j).toFloat()
                )

                poseStack.pushPose()
                poseStack.rotateAround(Axis.ZP.rotationDegrees(bodyRoll), x, y, 0f)
                RenderHelper.preciseBlit(
                    guiGraphics,
                    BOMB_SCOPE_PITCH,
                    x - 1.5f * i,
                    y - 1.5f * j - 4 * bodyPitch,
                    0f,
                    0f,
                    (3 * i).toFloat(),
                    (3 * j).toFloat(),
                    (3 * i).toFloat(),
                    (3 * j).toFloat()
                )
                renderKillIndicatorDynamic(
                    guiGraphics,
                    x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                    y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                )
                poseStack.popPose()
                poseStack.popPose()
                if (fixedWing) renderFlightAim(vehicle, player, guiGraphics, flightPresentation, screenWidth, screenHeight, partialTick)
                return
            }
        }

        if ((mc.options.cameraType == CameraType.FIRST_PERSON) && bodyVisible) {
            val x = p.x.toFloat()
            val y = p.y.toFloat()

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

            if (gunData?.get(GunProp.CROSSHAIR) == "@AirCraftMissile") {
                RenderHelper.blit(poseStack, HUD_BASE_MISSILE, x - 160, y - 160, 0f, 0f, 320f, 320f, 320f, 320f, color)
            } else {
                RenderHelper.blit(poseStack, HUD_BASE, x - 160, y - 160, 0f, 0f, 320f, 320f, 320f, 320f, color)
            }

            //指南针
            RenderHelper.blit(
                poseStack,
                COMPASS,
                x - 128,
                y - 122,
                128 + (64f / 45 * bodyYaw),
                0f,
                256f,
                16f,
                512f,
                16f,
                color
            )
            RenderHelper.blit(poseStack, COMPASS_IND, x - 4, y - 130, 0f, 0f, 8f, 8f, 8f, 8f, color)

            //滚转指示
            poseStack.pushPose()
            poseStack.rotateAround(Axis.ZP.rotationDegrees(bodyRoll), x, y + 48, 0f)
            RenderHelper.blit(poseStack, HELICOPTER_ROLL_IND, x - 4, y + 144, 0f, 0f, 8f, 8f, 8f, 8f, color)
            poseStack.popPose()

            val power = if (fixedWing) {
                flightPresentation?.throttle?.toFloat() ?: 0F
            } else vehicle.power
            lerpPower = Mth.lerp(0.5f * partialTick, lerpPower, power)

            RenderHelper.blit(
                poseStack,
                HelicopterHud.HELI_POWER,
                x - 105f,
                (y - 57f + 124f - Math.min(lerpPower, 1f) * 117.6f),
                0f,
                0f,
                4f,
                Math.min(lerpPower, 1f) * 117.6f,
                4f,
                Math.min(lerpPower, 1f) * 117.6f,
                color
            )

            if (lerpPower > 1) {
                RenderHelper.blit(
                    poseStack,
                    HelicopterHud.HELI_POWER,
                    x - 105f,
                    (y - 57f + 124f - (lerpPower - 1f) * 58.8f),
                    0f,
                    0f,
                    4f,
                    (lerpPower - 1f) * 58.8f,
                    4f,
                    (lerpPower - 1f) * 58.8f,
                    0xFF6B00
                )
            }

            RenderHelper.blit(
                poseStack,
                POWER_RULER,
                x - 135f,
                y - 57f,
                0f,
                0f,
                64f,
                128f,
                64f,
                128f,
                color
            )

            //一些文本

            poseStack.pushPose()
            poseStack.translate(x.toDouble(), y.toDouble(), 0.0)
            //时速
            guiGraphics.drawString(
                mc.font, Component.literal(format0D(speed)),
                -105, -61, color, false
            )

            //高度
            guiGraphics.drawString(
                mc.font, Component.literal(format0D(vehicle.y)),
                75, -61, color, false
            )

            //垂直速度
            guiGraphics.drawString(
                mc.font,
                Component.literal(FormatTool.DECIMAL_FORMAT_1ZZ.format(lerpVy.toDouble())),
                -96,
                60,
                color,
                false
            )
            if (fixedWing) {
                val presentedVehicleY = Mth.lerp(partialTick.toDouble(), vehicle.yo, vehicle.y)
                val mach = FixedWingHudMetrics.mach(
                    flightPresentation, presentedVehicleY, vehicle.level().seaLevel.toDouble(),
                    fixedWingStrategy?.handling?.simulationLengthScale ?: Double.NaN,
                )
                val liftG = FixedWingHudMetrics.signedLiftG(flightPresentation)
                val machText = mach?.let { FormatTool.DECIMAL_FORMAT_1ZZ.format(it) } ?: "—"
                val liftGText = liftG?.let { FormatTool.DECIMAL_FORMAT_1ZZ.format(it) } ?: "—"
                guiGraphics.drawString(mc.font, Component.literal("M $machText"), -105, 70, color, false)
                guiGraphics.drawString(mc.font, Component.literal("AERO G $liftGText"), -105, 78, color, false)
            } else {
                // Preserve native non-fixed-wing instrument behavior.
                lerpG =
                    Mth.lerp((0.25f * partialTick).toDouble(), lerpG.toDouble(), (400 * vehicle.getAcceleration()) / 9.8).toFloat()
                guiGraphics.drawString(mc.font, Component.literal("M"), -105, 70, color, false)
                guiGraphics.drawString(mc.font, Component.literal("0.2"), -96, 70, color, false)
                guiGraphics.drawString(mc.font, Component.literal("G"), -105, 78, color, false)
                guiGraphics.drawString(
                    mc.font,
                    Component.literal(FormatTool.DECIMAL_FORMAT_1ZZ.format(lerpG.toDouble())),
                    -96,
                    78,
                    color,
                    false
                )
            }

            // 热诱弹
            if (vehicle.hasDecoy()) {
                if (vehicle.decoyReady) {
                    guiGraphics.drawString(
                        Minecraft.getInstance().font,
                        Component.translatable("tips.superbwarfare.flare.ready").append(
                            Component.literal(
                                " [" + ModKeyMappings.RELEASE_DECOY.key.displayName.string + "]"
                            )
                        ),
                        72,
                        0,
                        color,
                        false
                    )
                } else {
                    guiGraphics.drawString(
                        Minecraft.getInstance().font,
                        Component.translatable("tips.superbwarfare.flare.reloading"),
                        72,
                        0,
                        0xFF0000,
                        false
                    )
                }
            }
            guiGraphics.drawString(mc.font, Component.literal("TGT"), 76, 78, color, false)

            // 武器名
            if (gunData != null) {
                val heat = vehicle.getWeaponHeat(player)
                val component = vehicle.firstPersonAmmoComponent(gunData, player)
                guiGraphics.drawString(
                    mc.font, component, -mc.font.width(component) / 2, 91,
                    getGradientColor(color, 0xFF0000, heat, 2), false
                )
            }

            // 能量警告
            if (vehicle.isOperationalPowerLimited() && vehicle.hasEnergyStorage()) {
                if (vehicle.energy < 0.02 * vehicle.maxEnergy) {
                    guiGraphics.drawString(
                        mc.font, Component.literal("NO POWER!"),
                        -144, 14, -65536, false
                    )
                } else if (vehicle.energy < 0.2 * vehicle.maxEnergy) {
                    guiGraphics.drawString(
                        mc.font, Component.literal("LOW POWER"),
                        -144, 14, 0xFF6B00, false
                    )
                }
            }

            poseStack.popPose()

            //框
            RenderHelper.blit(poseStack, HELICOPTER_SPEED_FRAME, x - 108, y - 64, 0f, 0f, 36f, 12f, 36f, 12f, color)
            RenderHelper.blit(
                poseStack,
                HELICOPTER_SPEED_FRAME,
                x + 108 - 36,
                y - 64,
                0f,
                0f,
                36f,
                12f,
                36f,
                12f,
                color
            )

            //角度
            poseStack.pushPose()

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

            poseStack.rotateAround(Axis.ZP.rotationDegrees(-bodyRoll), x, y, 0f)
            val pitch = bodyPitch
            RenderHelper.blit(
                poseStack,
                HUD_LINE,
                x - 144 + diffY,
                y - 128,
                0f,
                722.5f + 4.725f * pitch,
                288f,
                256f,
                288f,
                1701f,
                color
            )

            if (bomb) {
                RenderHelper.blit(poseStack, HUD_BOMB, x - 64 + diffY, y - 64, 0f, 0f, 128f, 128f, 128f, 128f, color)
            } else {
                RenderHelper.blit(poseStack, HUD_IND, x - 18 + diffY, y - 12, 0f, 0f, 36f, 24f, 36f, 24f, color)
            }

            poseStack.popPose()
        }

        poseStack.pushPose()

        if (crossVisible) {
            var x = pCross.x.toFloat()
            var y = pCross.y.toFloat()
            val xCross = x
            val yCross = y

            if (gunData != null && (mc.options.cameraType == CameraType.FIRST_PERSON) && (gunData.get(
                    GunProp.CROSSHAIR
                ) != "@AirBomb") && (gunData.get(GunProp.CROSSHAIR) != "@AirCraftMissile")
            ) {
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
                RenderHelper.blit(
                    poseStack,
                    HUD_BASE2,
                    x - 72 + diffY,
                    y - 72 + diffX,
                    0f,
                    0f,
                    144f,
                    144f,
                    144f,
                    144f,
                    color
                )
            } else if (mc.options.cameraType != CameraType.FIRST_PERSON) {
                if (gunData?.get(GunProp.CROSSHAIR) == "@AirBomb") {
                    bombHitPosX = Mth.lerp(0.25 * partialTick.toDouble(), bombHitPosX, xCross.toDouble())
                    bombHitPosY = Mth.lerp(0.25 * partialTick.toDouble(), bombHitPosY, yCross.toDouble())

                    RenderHelper.preciseBlit(
                        guiGraphics,
                        BOMB_RING,
                        bombHitPosX.toFloat() - 12f,
                        bombHitPosY.toFloat() - 12f,
                        0f,
                        0f,
                        24f,
                        24f,
                        24f,
                        24f
                    )
                    if (bodyVisible) {
                        x = p.x.toFloat()
                        y = p.y.toFloat()
                    }
                }

                mouseX = if (fixedWing) 0F else Mth.lerp(0.1f * partialTick, mouseX, ClientMouseHandler.lerpSpeedX.toFloat())
                mouseY = if (fixedWing) 0F else Mth.lerp(0.1f * partialTick, mouseY, ClientMouseHandler.lerpSpeedY.toFloat())
                RenderHelper.preciseBlit(guiGraphics,
                    HelicopterHud.RING, x - 2 + mouseX, y - 2 + mouseY, 0f, 0f, 4f, 4f, 4f, 4f)

                val originPos = Vec3(x.toDouble(), y.toDouble(), 0.0)
                val ringPos = Vec3(x + mouseX.toDouble(), y + mouseY.toDouble(), 0.0)

                val distance = ringPos.distanceTo(originPos)
                var i = 0.0
                while (i < distance - 3) {
                    val toVec = ringPos.vectorTo(originPos).normalize()
                    val p0 = ringPos.add(toVec.scale(i))
                    RenderHelper.blit(
                        poseStack,
                        HelicopterHud.BLOCK,
                        (p0.x - 0.25).toFloat(),
                        (p0.y - 0.25).toFloat(),
                        0f,
                        0f,
                        0.5f,
                        0.5f,
                        0.5f,
                        0.5f,
                        -1
                    )
                    i += 3
                }

                val pitch = bodyPitch
                RenderHelper.blit(
                    poseStack,
                    HUD_LINE_3P,
                    x - 96,
                    y - 48,
                    0f,
                    195 + 1.36f * pitch,
                    192f,
                    96f,
                    192f,
                    486f,
                    -1
                )

                RenderHelper.blit(
                    poseStack,
                    ROLL_HUD_3P,
                    x - 48,
                    y - 48,
                    0f,
                    0f,
                    96f,
                    96f,
                    96f,
                    96f,
                    -1
                )

                poseStack.pushPose()
                poseStack.rotateAround(Axis.ZP.rotationDegrees(bodyRoll), x, y, 0f)
                RenderHelper.preciseBlit(guiGraphics,
                    HelicopterHud.CROSSHAIR_3P, x - 34, y - 8.5f, 0f, 0f, 68f, 17f, 68f, 17f)
                renderKillIndicatorDynamic(
                    guiGraphics,
                    x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                    y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                )

                //
                poseStack.pushPose()
                poseStack.translate(x, y, 0f)
                poseStack.scale(0.75f, 0.75f, 1f)
                guiGraphics.drawString(
                    Minecraft.getInstance().font,
                    Component.translatable(format0D(bodyRoll.toDouble()) + "°"),
                    -42,
                    -9,
                    -1,
                    false
                )
                guiGraphics.drawString(
                    Minecraft.getInstance().font,
                    Component.translatable(format0D(vehicle.y) + "m"),
                    -42,
                    2,
                    -1,
                    false
                )

                poseStack.popPose()
                //

                poseStack.popPose()

                // 时速
                //
                poseStack.pushPose()
                poseStack.translate(x, y, 0f)
                poseStack.scale(0.75f, 0.75f, 1f)
                guiGraphics.drawString(
                    mc.font,
                    Component.literal(format0D(speed, "km/h")),
                    -60,
                    -52,
                    -1,
                    false
                )

                val component = Component.literal(format0D(lerpVy.toDouble()) + "m/s")
                val font = Minecraft.getInstance().font

                guiGraphics.drawString(font, component, 60 - font.width(component), -52, -1, false)
                poseStack.popPose()

                poseStack.pushPose()
                poseStack.translate(x, y + 50, 0f)
                poseStack.scale(0.75f, 0.75f, 1f)

                if (gunData != null && !fixedWing) {
                    VehicleMainWeaponHudOverlay.renderWeaponInfoThirdAir(guiGraphics, vehicle, player, gunData, font)
                }

                if (vehicle.hasDecoy()) {
                    if (vehicle.decoyReady) {
                        val componentReady = Component.translatable("tips.superbwarfare.flare.ready").append(
                            Component.literal(
                                " [" + ModKeyMappings.RELEASE_DECOY.key.displayName.string + "]"
                            )
                        )
                        val length = font.width(componentReady)

                        guiGraphics.drawString(
                            Minecraft.getInstance().font,
                            componentReady,
                            -length / 2,
                            1,
                            -1,
                            false
                        )
                    } else {
                        val componentReloading = Component.translatable("tips.superbwarfare.flare.reloading")
                        val length = font.width(componentReloading)

                        guiGraphics.drawString(
                            Minecraft.getInstance().font,
                            componentReloading,
                            -length / 2,
                            1,
                            0xFF0000,
                            false
                        )
                    }
                }

                poseStack.popPose()
            }
        }
        poseStack.popPose()
        poseStack.popPose()
        if (fixedWing) {
            renderFlightAim(vehicle, player, guiGraphics, flightPresentation, screenWidth, screenHeight, partialTick)
            com.atsuishio.superbwarfare.client.overlay.VehicleSystemsHud.aircraft(
                guiGraphics, vehicle, player, screenWidth, flightPresentation?.throttle?.toDouble(),
                FixedWingHudMetrics.speedKmh(flightPresentation))
        }
    }

    private fun renderFlightAim(
        vehicle: VehicleEntity,
        player: Player,
        graphics: GuiGraphics,
        flight: VehicleFlightInstrumentSnapshot?,
        width: Int,
        height: Int,
        partialTick: Float,
    ) {
        if (flight?.controlSurfaces?.wheelBrakeActive == true) {
            val braking = Component.literal("BRAKING")
            graphics.drawString(mc.font, braking, width - mc.font.width(braking) - 12,
                height - 122, 0xFFFFAD40.toInt(), false)
        }
        if ((flight?.controlSurfaces?.airbrake ?: 0F) > 0.01F) {
            val label = Component.literal("AIRBRAKE")
            graphics.drawString(mc.font, label, width - mc.font.width(label) - 12,
                height - 134, 0xFFFFAD40.toInt(), false)
        }
        val view = FixedWingPilotIntentClient.activeView(player) ?: return
        val target = FixedWingPilotIntent.normalized(view.directionX, view.directionY, view.directionZ, 0) ?: return
        val matrix = ClientEventHandler.modelViewMatrix ?: return
        val projection = ClientEventHandler.projectionMatrix ?: return
        val marker = FixedWingMouseAimMath.project(target.directionX, target.directionY, target.directionZ,
            matrix, projection, width, height) ?: return
        val color = 0xFFFFAD40.toInt()
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(marker.x, marker.y, 0f)
        val buffer = graphics.bufferSource().getBuffer(RenderType.gui())
        FixedWingJoystickRing.emit(pose.last().pose(), buffer, color)
        pose.popPose()
        // Three short ticks from the steering circle toward the aircraft's frontal projection (where the nose
        // points), each a small bar across that direction, evenly spaced; a tick that would reach the nose marker
        // is left out, so they vanish one by one as the nose closes on the circle.
        val nose = net.minecraft.world.phys.Vec3.directionFromRotation(vehicle.getPitch(partialTick),
            vehicle.getResolvedChassisYaw(partialTick))
        val noseMarker = FixedWingMouseAimMath.project(nose.x, nose.y, nose.z, matrix, projection, width, height)
        if (noseMarker != null) {
            val dx = noseMarker.x - marker.x
            val dy = noseMarker.y - marker.y
            val distance = kotlin.math.hypot(dx, dy)
            if (distance > 1e-3f) {
                val ux = dx / distance; val uy = dy / distance
                val m = pose.last().pose()
                val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                for (i in 0 until JOYSTICK_TICKS) {
                    val along = FixedWingJoystickRing.OUTER_RADIUS + TICK_GAP + i * TICK_SPACING
                    if (along > distance - NOSE_CLEARANCE) break
                    val cx = marker.x + ux * along; val cy = marker.y + uy * along
                    // half-length across the direction (-uy, ux), half-width along it (ux, uy)
                    val ax = -uy * TICK_HALF_LENGTH; val ay = ux * TICK_HALF_LENGTH
                    val wx = ux * TICK_HALF_WIDTH; val wy = uy * TICK_HALF_WIDTH
                    buffer.vertex(m, cx - ax - wx, cy - ay - wy, 0f).color(r, g, b, TICK_ALPHA).endVertex()
                    buffer.vertex(m, cx + ax - wx, cy + ay - wy, 0f).color(r, g, b, TICK_ALPHA).endVertex()
                    buffer.vertex(m, cx + ax + wx, cy + ay + wy, 0f).color(r, g, b, TICK_ALPHA).endVertex()
                    buffer.vertex(m, cx - ax + wx, cy - ay + wy, 0f).color(r, g, b, TICK_ALPHA).endVertex()
                }
            }
        }
        graphics.flush()
        if (flight?.controlSurfaces?.afterburnerActive == true) {
            val boost = Component.translatable("hud.superbwarfare.afterburner")
            graphics.drawString(mc.font, boost, width - mc.font.width(boost) - 12,
                height - 109, 0xFFFFAD40.toInt(), false)
        }
    }
}
