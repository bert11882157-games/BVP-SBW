package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.VehicleGeometricZeroDistanceClient
import com.atsuishio.superbwarfare.client.VehicleHudScaleController
import com.atsuishio.superbwarfare.client.camera.VehicleOpticalZoomController
import com.atsuishio.superbwarfare.config.client.DisplayConfig
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.client.overlay.weapon.LandVehicleHud
import com.atsuishio.superbwarfare.network.VehicleGeometricZeroWireStatus
import com.atsuishio.superbwarfare.tools.FormatTool
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import org.joml.Math
import top.theillusivec4.curios.api.CuriosApi

@OnlyIn(Dist.CLIENT)
object VehicleHudOverlay : CommonOverlay("vehicle_hud") {
    private val ARMOR = loc("textures/overlay/vehicle/base/armor.png")
    private val ENERGY = loc("textures/overlay/vehicle/base/energy.png")
    private val VALUE_BAR = loc("textures/overlay/vehicle/base/value_bar.png")
    private val VALUE_FRAME = loc("textures/overlay/vehicle/base/value_frame.png")

    private val DRIVER = loc("textures/overlay/vehicle/base/driver.png")
    private val PASSENGER = loc("textures/overlay/vehicle/base/passenger.png")

    private val HIT_MARKER = loc("textures/overlay/crosshair/hit_marker.png")
    private val HIT_MARKER_VEHICLE = loc("textures/overlay/crosshair/hit_marker_vehicle.png")
    private val HEADSHOT_MARKER = loc("textures/overlay/crosshair/headshot_marker.png")
    private val KILL_MARKER_1 = loc("textures/overlay/crosshair/kill_marker_1.png")
    private val KILL_MARKER_2 = loc("textures/overlay/crosshair/kill_marker_2.png")
    private val KILL_MARKER_3 = loc("textures/overlay/crosshair/kill_marker_3.png")
    private val KILL_MARKER_4 = loc("textures/overlay/crosshair/kill_marker_4.png")

    override fun RenderContext.render() {
        VehicleHudScaleController.sync(player)
        if (!shouldRenderHud(player)) {
            return
        }

        val entity = player.vehicle
        if (entity !is VehicleEntity) return

        val poseStack = guiGraphics.pose()
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

        // Ground module state is drawn by GroundVehicleStatusHud, including BVP providers.
        if (entity.computed().hudType != LandVehicleHud.ID) {
            val compatHeight: Int = getArmorPlateCompatHeight(player)

            if (entity.isOperationalPowerLimited() && entity.hasEnergyStorage()) {
                val energy = entity.energy.toFloat()
                val maxEnergy = entity.maxEnergy.toFloat()

                RenderHelper.preciseBlit(
                    guiGraphics,
                    ENERGY,
                    10f,
                    (screenHeight - 22 - compatHeight).toFloat(),
                    100f,
                    0f,
                    0f,
                    8f,
                    8f,
                    8f,
                    8f
                )
                RenderHelper.preciseBlit(
                    guiGraphics,
                    VALUE_FRAME,
                    20f,
                    (screenHeight - 21 - compatHeight).toFloat(),
                    100f,
                    0f,
                    0f,
                    60f,
                    6f,
                    60f,
                    6f
                )
                RenderHelper.preciseBlit(
                    guiGraphics,
                    VALUE_BAR,
                    20f,
                    (screenHeight - 21 - compatHeight).toFloat(),
                    100f,
                    0f,
                    0f,
                    (60 * energy / maxEnergy).toInt().toFloat(),
                    6f,
                    60f,
                    6f
                )
            }

            val health = entity.health
            val maxHealth = entity.getMaxHealth()

            RenderHelper.preciseBlit(
                guiGraphics,
                ARMOR,
                10f,
                (screenHeight - 13 - compatHeight).toFloat(),
                100f,
                0f,
                0f,
                8f,
                8f,
                8f,
                8f
            )
            RenderHelper.preciseBlit(
                guiGraphics,
                VALUE_FRAME,
                20f,
                (screenHeight - 12 - compatHeight).toFloat(),
                100f,
                0f,
                0f,
                60f,
                6f,
                60f,
                6f
            )
            RenderHelper.preciseBlit(
                guiGraphics,
                VALUE_BAR,
                20f,
                (screenHeight - 12 - compatHeight).toFloat(),
                100f,
                0f,
                0f,
                (60 * health / maxHealth).toInt().toFloat(),
                6f,
                60f,
                6f
            )
        }

        renderWeaponInfo(guiGraphics, entity, screenWidth, screenHeight)
        renderGroundSpeed(guiGraphics, entity, partialTick, screenWidth, screenHeight)
        renderGroundZero(guiGraphics, entity, screenWidth, screenHeight)
        renderPassengerInfo(guiGraphics, entity, screenWidth, screenHeight)
        renderOpticalZoomInfo(guiGraphics, player, entity, screenWidth, screenHeight)

        poseStack.popPose()
    }

    /** Shared bottom-left module/alignment display and fixed-font speed line. */
    private fun renderGroundSpeed(
        guiGraphics: GuiGraphics,
        vehicle: VehicleEntity,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (vehicle.computed().hudType != LandVehicleHud.ID) return
        GroundVehicleStatusHud.render(guiGraphics, vehicle, partialTick, screenWidth, screenHeight)
    }

    /**
     * Read-only sight-zero status below the module display; the zero client owns input/networking.
     */
    private fun renderGroundZero(
        guiGraphics: GuiGraphics,
        vehicle: VehicleEntity,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        if (vehicle.computed().hudType != LandVehicleHud.ID) return
        val player = Minecraft.getInstance().player ?: return
        val key = ModKeyMappings.VEHICLE_CYCLE_SIGHT_ZERO.key.displayName.string
        val seat = vehicle.getSeatIndex(player)
        val text = if (VehicleLaserRangefinder.enabled(vehicle, seat, vehicle.getSelectedWeapon(seat))) {
            val state = VehicleGeometricZeroDistanceClient.activeFcsState(player)
            if (state?.status == VehicleGeometricZeroWireStatus.SOLUTION) {
                val distance = state.measuredRangeBlocks?.takeIf { it.isFinite() && it >= 0.0 } ?: return
                "[$key] Zero ${FormatTool.format1DZ(distance)} m"
            } else when (state?.status) {
                VehicleGeometricZeroWireStatus.NO_BLOCK_HIT -> "[$key] LRF: no return"
                VehicleGeometricZeroWireStatus.NO_SOLUTION -> "[$key] LRF: no ballistic solution"
                else -> "[$key] Laser rangefinder"
            }
        } else {
            val distance = VehicleGeometricZeroDistanceClient.activeDistance(player) ?: return
            "[$key] Zero $distance"
        }
        drawBounded(
            guiGraphics,
            text,
            12,
            GroundVehicleStatusHud.auxiliaryY(screenHeight, 1),
            GroundVehicleStatusHud.textWidth(screenWidth),
            vehicle.hudColor,
            HudTextAlign.LEFT,
        )
    }

    private fun renderOpticalZoomInfo(
        guiGraphics: GuiGraphics,
        player: Player,
        vehicle: VehicleEntity,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val zoom = VehicleOpticalZoomController.activeView(player) ?: return
        val text = Component.literal("ZOOM ${FormatTool.format1DZ(zoom.magnification, "×")}")
        if (vehicle.computed().hudType == LandVehicleHud.ID) {
            drawBounded(
                guiGraphics,
                text.string,
                12,
                GroundVehicleStatusHud.auxiliaryY(screenHeight, 3),
                GroundVehicleStatusHud.textWidth(screenWidth),
                vehicle.hudColor,
                HudTextAlign.LEFT,
            )
        } else {
            val font = Minecraft.getInstance().font
            val x = (screenWidth - font.width(text)) / 2
            guiGraphics.drawString(font, text, x, screenHeight - 42, vehicle.hudColor, true)
        }
    }

    private fun shouldRenderHud(player: Player?): Boolean {
        if (player == null) return false
        return !player.isSpectator && player.vehicle is VehicleEntity
    }

    private fun getArmorPlateCompatHeight(player: Player): Int {
        val stack = player.getItemBySlot(EquipmentSlot.CHEST)
        if (stack == ItemStack.EMPTY) return 0
        if (stack.tag == null || !stack.tag!!.contains("ArmorPlate")) return 0
        if (!DisplayConfig.ARMOR_PLATE_HUD.get()) return 0
        return 9
    }

    @JvmStatic
    fun renderKillIndicator(guiGraphics: GuiGraphics?, w: Float, h: Float) {
        val posX = w / 2f - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
        val posY = h / 2f - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
        val rate = (40 - CrossHairOverlay.killIndicator * 5) / 5.5f

        if (CrossHairOverlay.hitIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HIT_MARKER, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.vehicleIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HIT_MARKER_VEHICLE, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.headIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HEADSHOT_MARKER, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.killIndicator > 0) {
            val posX1 = w / 2f - 7.5f - 2 + rate
            val posY1 = h / 2f - 7.5f - 2 + rate
            val posX2 = w / 2f - 7.5f + 2 - rate
            val posY2 = h / 2f - 7.5f + 2 - rate

            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_1, posX1, posY1, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_2, posX2, posY1, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_3, posX1, posY2, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_4, posX2, posY2, 0f, 0f, 16f, 16f, 16f, 16f)
        }
    }

    @JvmStatic
    fun renderKillIndicatorDynamic(guiGraphics: GuiGraphics?, posX: Float, posY: Float) {
        val rate = (40 - CrossHairOverlay.killIndicator * 5) / 5.5f

        if (CrossHairOverlay.hitIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HIT_MARKER, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.vehicleIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HIT_MARKER_VEHICLE, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.headIndicator > 0) {
            RenderHelper.preciseBlit(guiGraphics, HEADSHOT_MARKER, posX, posY, 0f, 0f, 16f, 16f, 16f, 16f)
        }

        if (CrossHairOverlay.killIndicator > 0) {
            val posX1 = posX - 2 + rate
            val posY1 = posY - 2 + rate
            val posX2 = posX + 2 - rate
            val posY2 = posY + 2 - rate

            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_1, posX1, posY1, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_2, posX2, posY1, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_3, posX1, posY2, 0f, 0f, 16f, 16f, 16f, 16f)
            RenderHelper.preciseBlit(guiGraphics, KILL_MARKER_4, posX2, posY2, 0f, 0f, 16f, 16f, 16f, 16f)
        }
    }

    private fun renderPassengerInfo(guiGraphics: GuiGraphics, vehicle: VehicleEntity, w: Int, h: Int) {
        val font = Minecraft.getInstance().font
        val passengers = vehicle.getOrderedPassengers()

        for ((index, i) in passengers.indices.reversed().withIndex()) {
            val passenger = passengers[i]

            val ground = vehicle.computed().hudType == LandVehicleHud.ID
            val y = if (ground) GroundHudLayout.passengerRowY(h, index) else h - 35 - index * 12
            var name = "---"

            if (passenger != null) {
                name = passenger.name.string
            }

            if (passenger is Player) {
                CuriosApi.getCuriosInventory(passenger).ifPresent { c ->
                    c.findFirstCurio(ModItems.DOG_TAG.get()).ifPresent { s ->
                        if (s.stack().hasCustomHoverName()) {
                            name = s.stack().getHoverName().string
                        }
                    }
                }
            }

            guiGraphics.drawString(font, if(ground) font.plainSubstrByWidth(name,72) else name,
                42, y, 0x66ff00, true)

            val num = "[" + (i + 1) + "]"
            guiGraphics.drawString(
                font,
                num,
                25 - font.width(num),
                y,
                0x66ff00,
                true
            )

            RenderHelper.preciseBlit(
                guiGraphics,
                if (index == passengers.size - 1) DRIVER else PASSENGER,
                30f,
                y.toFloat(),
                100f,
                0f,
                0f,
                8f,
                8f,
                8f,
                8f
            )
        }
    }

    /**
     * Responsive ground-vehicle panel.  The scaled viewport is divided into a 3x3 grid so every
     * anchor follows the current GUI-scaled width/height instead of screenshot coordinates.
     */
    private fun renderWeaponInfo(guiGraphics: GuiGraphics, vehicle: VehicleEntity, w: Int, h: Int) {
        val player = Minecraft.getInstance().player ?: return
        if (vehicle.computed().hudType != LandVehicleHud.ID) return
        if (!vehicle.banHand(player)) return

        VehicleSystemsHud.ground(guiGraphics, vehicle, player, w, h)
    }

    private fun displayAmmoName(data: GunData): String {
        val belt = data.get(GunProp.PROJECTILE_BELT)
        if (belt != null && belt.isValid()) {
            when (belt.family) {
                com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.RUSSIAN_127,
                com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.WESTERN_127 ->
                    return "12.7mm Belt"
                com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.RUSSIAN_COAX_762,
                com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.WESTERN_COAX_762 ->
                    return "7.62mm Belt"
                com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.KPVT ->
                    return displayKpvtBeltLabel(belt)
                else -> belt.name.takeIf { it.isNotBlank() }?.let { return sanitizeDisplayText(stripDisplayCountSuffix(it)) }
            }
        }

        val selectedName = data.get(GunProp.NAME)
        val baseName = data.getDefault().name
        if (!selectedName.isNullOrBlank() && selectedName != baseName &&
            !isGenericDescriptor(selectedName)
        ) {
            val translated = sanitizeDisplayText(stripDisplayCountSuffix(Component.translatable(selectedName).string))
            if (!isGenericDescriptor(translated)) return translated
        }

        val consumer = data.selectedAmmoConsumer()
        return when {
            consumer.type == AmmoConsumer.AmmoConsumeType.INFINITE -> "Infinity"
            consumer.type == AmmoConsumer.AmmoConsumeType.ENERGY -> "Energy"
            !consumer.stack().isEmpty -> {
                val resourceId = consumer.stack().descriptionId.lowercase()
                val display = consumer.stack().hoverName.string
                if (resourceId.contains("level_") || resourceId.contains("anti_ground") ||
                    resourceId.contains("large_caliber") || resourceId.endsWith("_ammo") ||
                    isGenericDescriptor(resourceId) || isGenericDescriptor(display)) {
                    ""
                } else {
                    sanitizeDisplayText(stripDisplayCountSuffix(display)).takeIf { it.isNotBlank() } ?: ""
                }
            }
            else -> ""
        }
    }

    private fun displayWeaponName(data: GunData): String {
        val key = data.getDefault().name?.takeIf { it.isNotBlank() } ?: data.get(GunProp.NAME)
        if (key == null || isGenericDescriptor(key)) return "unavailable"
        // A typed KPVT belt is a 14.5 mm cannon even if an older translated/default key is
        // accidentally shared with a coax entry; never let the generic coax alias erase the
        // authored KPVT platform name.
        if (!isKpvtBelt(data) && (key == "weapon.superbwarfare.7_62mm_coax" || isCoaxBelt(data))) {
            return "7.62mm Coax"
        }
        val translated = sanitizeDisplayText(stripDisplayCountSuffix(Component.translatable(key).string))
        return if (isGenericDescriptor(translated)) "unavailable" else translated
    }

    private fun loadedAmmoLabel(vehicle: VehicleEntity, data: GunData): String {
        val available = vehicle.getAmmo(data)
        val count = if (available == Int.MAX_VALUE) "∞" else available.coerceAtLeast(0).toString()
        if (data.reloading()) return "$count · RELOADING"
        val cost = data.get(GunProp.AMMO_COST_PER_SHOOT)
        return if (available < cost && data.backupAmmoCount.get() < cost) "$count · AMMO NEEDED" else count
    }

    /** KPVT is a 14.5 mm cannon belt, never the 7.62 mm coax family. */
    private fun displayKpvtBeltLabel(belt: com.atsuishio.superbwarfare.data.gun.ProjectileBeltProfile): String {
        val authored = belt.name.takeIf { it.isNotBlank() }
            ?.let { sanitizeDisplayText(stripDisplayCountSuffix(it)) }
            ?.takeIf { it.isNotBlank() }
            ?: "Belt"
        return if (authored.contains("14.5", ignoreCase = true)) authored else "14.5mm $authored"
    }

    private fun isKpvtBelt(data: GunData): Boolean =
        data.get(GunProp.PROJECTILE_BELT)?.isKpvtTyped() == true

    private fun isCoaxBelt(data: GunData): Boolean {
        val family = data.get(GunProp.PROJECTILE_BELT)?.family ?: return false
        return family == com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.RUSSIAN_COAX_762 ||
            family == com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily.WESTERN_COAX_762
    }

    /** Remove unresolved translation placeholders before a name reaches the HUD. */
    private fun sanitizeDisplayText(value: String): String {
        var result = value
        var marker = result.indexOf('%')
        while (marker >= 0 && marker + 3 < result.length) {
            var cursor = marker + 1
            while (cursor < result.length && result[cursor].isDigit()) cursor++
            if (cursor > marker + 1 && cursor + 1 < result.length && result[cursor] == '$' &&
                result[cursor + 1].isLetter()
            ) {
                result = (result.substring(0, marker) + result.substring(cursor + 2)).trim()
                marker = result.indexOf('%', marker.coerceAtMost(result.length))
            } else {
                marker = result.indexOf('%', marker + 1)
            }
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /** Remove count decorations that belong to an item/translation label, not the live magazine. */
    private fun stripDisplayCountSuffix(value: String): String {
        var end = value.length
        while (end > 0 && value[end - 1].isWhitespace()) end--
        if (end < 2) return value.trim()
        var digitStart = end
        while (digitStart > 0 && value[digitStart - 1].isDigit()) digitStart--
        if (digitStart == end) return value.trim()
        var marker = digitStart
        if (marker > 0 && (value[marker - 1] == 's' || value[marker - 1] == 'S')) marker--
        if (marker > 0 && (value[marker - 1] == 'x' || value[marker - 1] == 'X' || value[marker - 1] == '×')) {
            marker--
            while (marker > 0 && value[marker - 1].isWhitespace()) marker--
            return value.substring(0, marker).trimEnd()
        }
        return value.trim()
    }

    /** Hide generic SBW tier/category descriptors while leaving authored data untouched. */
    private fun isGenericDescriptor(value: String?): Boolean {
        val normalized = value?.lowercase()?.replace('_', ' ')?.replace('-', ' ') ?: return false
        return normalized.contains("level 2 ap shell") ||
            normalized.contains("small caliber armor piercing") ||
            normalized.contains("large caliber armor piercing") ||
            normalized.contains("medium anti ground missile") ||
            normalized.contains("small anti ground missile") ||
            normalized.contains("large anti ground missile")
    }

    private fun displayWeaponLine(data: GunData): String =
        displayWeaponName(data).let { platform ->
            displayAmmoName(data).takeIf { it.isNotBlank() && !it.equals(platform, ignoreCase = true) }
                ?.let { "$platform · $it" } ?: platform
        }

    private enum class HudTextAlign { LEFT, CENTER, RIGHT }

    /** Draw at one fixed HUD scale; long labels are clipped by deterministic ellipsis only. */
    private fun drawBounded(
        guiGraphics: GuiGraphics,
        text: String,
        anchorX: Int,
        y: Int,
        maxWidth: Int,
        color: Int,
        align: HudTextAlign,
    ) {
        val font = Minecraft.getInstance().font
        val boundedWidth = maxWidth.coerceAtLeast(1)
        val shown = if (font.width(text) <= boundedWidth) text else {
            val ellipsis = "…"
            val prefixWidth = (boundedWidth - font.width(ellipsis)).coerceAtLeast(0)
            font.plainSubstrByWidth(text, prefixWidth) + ellipsis
        }
        val shownWidth = font.width(shown)
        val drawX = when (align) {
            HudTextAlign.LEFT -> 0
            HudTextAlign.CENTER -> -shownWidth / 2
            HudTextAlign.RIGHT -> -shownWidth
        }
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(anchorX.toFloat(), y.toFloat(), 0f)
        guiGraphics.drawString(font, shown, drawX, 0, color, true)
        pose.popPose()
    }

}
