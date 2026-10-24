package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.api.vehicle.flight.RotorcraftControlGauges
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicatorDynamic
import com.atsuishio.superbwarfare.client.overlay.VehicleSystemsHud
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.canBeSeen
import com.atsuishio.superbwarfare.tools.worldToScreen
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.gui.overlay.ForgeGui

/**
 * Helicopter HUD: the bottom-right weapon systems panel shared with ground vehicles, the pilot's power gauge
 * for helicopters that do not draw their own collective and throttle ([RotorcraftControlGauges]), and the
 * small aim cue where the selected weapon points. The legacy SBW attitude, compass, speed, altitude, TV frame,
 * crosshair-adjacent weapon label and flare text are intentionally not drawn.
 */
@OnlyIn(Dist.CLIENT)
object HelicopterHud {
    const val ID: String = "@Helicopter"

    val HELI_POWER_RULER = loc("textures/overlay/vehicle/helicopter/heli_power_ruler.png")
    val HELI_POWER = loc("textures/overlay/vehicle/helicopter/heli_power.png")
    private val CROSSHAIR_IND = loc("textures/overlay/vehicle/helicopter/crosshair_ind.png")

    // Shared with AircraftHud.
    val CROSSHAIR_3P = loc("textures/overlay/vehicle/crosshair/third_camera_2.png")
    val RING = loc("textures/overlay/vehicle/aircraft/rex_circle.png")
    val BLOCK = loc("textures/overlay/misc/block_white.png")

    private var lerpPower = 0f

    /** Helicopters use this HUD whether their data names `@Helicopter` or `@Aircraft`. */
    @JvmStatic
    fun handles(vehicle: VehicleEntity, hudType: String): Boolean =
        hudType == ID || (hudType == AircraftHud.ID && vehicle.vehicleType == VehicleType.HELICOPTER)

    fun render(
        vehicle: VehicleEntity,
        player: Player,
        gui: ForgeGui,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (vehicle !is RotorcraftControlGauges && player === vehicle.getFirstPassenger()) {
            renderPowerGauge(vehicle, guiGraphics, partialTick, screenWidth, screenHeight)
        }
        renderAimCue(vehicle, player, guiGraphics, partialTick)
        VehicleSystemsHud.ground(guiGraphics, vehicle, player, screenWidth, screenHeight, true)
    }

    /**
     * Many helicopter weapons author an empty crosshair, and VehicleCrosshairOverlay draws none for the pilot in
     * third person, so this cue is the only aim point for those. A turret gunner keeps the turret crosshair.
     */
    private fun renderAimCue(vehicle: VehicleEntity, player: Player, guiGraphics: GuiGraphics, partialTick: Float) {
        if (vehicle.getGunData(player) == null) return
        if (vehicle.hasTurret() && vehicle.getSeatIndex(player) == vehicle.computed().turretControllerIndex) return
        // A fixed horizon keeps the cue free of a per-frame HUD range trace.
        val pos = vehicle.getShootPosForHud(player, partialTick)
            .add(vehicle.getShootDirectionForHud(player, partialTick).scale(512.0))
        if (!pos.canBeSeen()) return
        val screen = pos.worldToScreen()
        val x = screen.x.toFloat()
        val y = screen.y.toFloat()
        RenderHelper.blit(guiGraphics.pose(), CROSSHAIR_IND, x - 8, y - 8, 0f, 0f, 16f, 16f, 16f, 16f, vehicle.hudColor)
        renderKillIndicatorDynamic(
            guiGraphics,
            x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
            y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
        )
    }

    private fun renderPowerGauge(
        vehicle: VehicleEntity,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val poseStack = guiGraphics.pose()
        val color = vehicle.hudColor
        RenderHelper.blit(
            poseStack, HELI_POWER_RULER,
            screenWidth / 2f + 100, screenHeight / 2f - 64,
            0f, 0f, 64f, 128f, 64f, 128f, color
        )
        lerpPower = Mth.lerp(0.5f * partialTick, lerpPower, vehicle.power)
        val height = lerpPower * 980
        RenderHelper.blit(
            poseStack, HELI_POWER,
            screenWidth / 2f + 130, screenHeight / 2f - 64 + 124 - height,
            0f, 0f, 4f, height, 4f, height, color
        )
    }
}
