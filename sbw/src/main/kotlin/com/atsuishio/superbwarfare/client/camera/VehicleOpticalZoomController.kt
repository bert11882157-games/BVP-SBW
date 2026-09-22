package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClickEventHandler
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.notInGame
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Player
import java.util.EnumMap
import java.util.UUID

/** Stable optical roles; selections never depend on entity IDs or selected weapon indexes. */
enum class VehicleOpticalZoomProfile(
    val defaultMagnification: Double,
    val minMagnification: Double,
    val maxMagnification: Double,
) {
    TANK_PRIMARY(2.0, 2.0, 12.0),
    TANK_PASSENGER_HMG(2.0, 2.0, 4.0),
    HELICOPTER_PILOT(2.0, 2.0, 18.0),
    HELICOPTER_GUNNER(2.0, 2.0, 24.0),
}

private data class ZoomBounds(val min: Double, val max: Double)

/** Immutable read-only HUD/FX view. A null view means vehicle optical zoom is not relevant. */
data class VehicleOpticalZoomView(
    val profile: VehicleOpticalZoomProfile,
    val magnification: Double,
    val minMagnification: Double,
    val maxMagnification: Double,
    val heldActive: Boolean,
)

/**
 * Client-only held optical-zoom state.
 *
 * Magnification selections persist for this client process and across holds/world changes, but are
 * intentionally not written to disk. The four enum keys isolate seat roles from vehicle identity
 * and weapon selection. No state in this controller is sent to the server or mutates vehicle aim.
 */
object VehicleOpticalZoomController {
    private data class Context(
        val vehicle: VehicleEntity,
        val seatIndex: Int,
        val profile: VehicleOpticalZoomProfile,
        val bounds: ZoomBounds,
    )

    private data class HoldSession(
        val playerUuid: UUID,
        val level: Any,
        val vehicle: VehicleEntity,
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val profile: VehicleOpticalZoomProfile,
        val pressedInput: InputConstants.Key,
        var scrollRemainder: Double = 0.0,
    )

    private val magnifications = EnumMap<VehicleOpticalZoomProfile, Double>(VehicleOpticalZoomProfile::class.java).apply {
        VehicleOpticalZoomProfile.entries.forEach { put(it, it.defaultMagnification) }
    }

    private var session: HoldSession? = null

    /** Begins only from the exact HOLD binding caller; toggle zoom never calls this method. */
    @JvmStatic
    fun beginHold(player: Player, input: InputConstants.Key) {
        if (session != null || notInGame || !ClientEventHandler.zoomVehicle) return
        if (!ModKeyMappings.VEHICLE_HOLD_ZOOM.isActiveAndMatches(input)) return
        val context = resolveContext(player) ?: return
        val level = mc.level ?: return
        session = HoldSession(
            playerUuid = player.uuid,
            level = level,
            vehicle = context.vehicle,
            vehicleUuid = context.vehicle.uuid,
            seatIndex = context.seatIndex,
            profile = context.profile,
            pressedInput = input,
        )
    }

    /** Exact recorded-key release; modifier changes after press do not interfere with cleanup. */
    @JvmStatic
    fun releaseHold(input: InputConstants.Key) {
        val active = session ?: return
        if (active.pressedInput == input) clearHold(active)
    }

    /** Runs with a nullable player so GUI/focus/world/death/rebind/lost-release transitions fail closed. */
    @JvmStatic
    fun tick(player: LocalPlayer?) {
        val active = session ?: return
        val contextMatches = contextMatches(active, player)
        val bindingMatches = ModKeyMappings.VEHICLE_HOLD_ZOOM.key == active.pressedInput
        if (!contextMatches || !bindingMatches || notInGame || !ClientEventHandler.zoomVehicle) {
            clearHold(active)
        }
    }

    /**
     * Consumes one nonzero wheel event only for a live held optical session. Positive is zoom-in;
     * negative is zoom-out. Fractional wheel input is accumulated; each full notch advances
     * exactly one 0.5x step and remains clamped.
     */
    @JvmStatic
    fun adjustFromScroll(player: Player, scrollDelta: Double): Boolean {
        if (!scrollDelta.isFinite() || scrollDelta == 0.0 || notInGame || VehicleFreeCameraController.isActive(player)) return false
        val active = activeSession(player) ?: return false
        val profile = active.profile
        val bounds = resolveContext(player)?.bounds ?: return false
        active.scrollRemainder += scrollDelta
        val wholeNotches = active.scrollRemainder.toInt()
        if (wholeNotches != 0) {
            active.scrollRemainder -= wholeNotches
            magnifications[profile] = (selected(profile, bounds) + wholeNotches * ZOOM_STEP)
                .coerceIn(bounds.min, bounds.max)
        }
        return true
    }

    /** Null means no supported armed vehicle role; heldActive identifies the exact FOV-driving path. */
    @JvmStatic
    fun currentView(player: Player? = mc.player): VehicleOpticalZoomView? {
        val currentPlayer = player ?: return null
        val context = resolveContext(currentPlayer) ?: return null
        val active = activeSession(currentPlayer)?.profile == context.profile
        return context.profile.view(context.bounds, active)
    }

    /** Exact clamped divisor used by held vehicle zoom; null preserves the legacy/toggle FOV path. */
    @JvmStatic
    fun activeView(player: Player? = mc.player): VehicleOpticalZoomView? {
        val currentPlayer = player ?: return null
        val active = activeSession(currentPlayer) ?: return null
        val context = resolveContext(currentPlayer) ?: return null
        return context.profile.view(context.bounds, heldActive = true)
    }

    private fun VehicleOpticalZoomProfile.view(bounds: ZoomBounds, heldActive: Boolean) = VehicleOpticalZoomView(
        profile = this,
        magnification = selected(this, bounds),
        minMagnification = bounds.min,
        maxMagnification = bounds.max,
        heldActive = heldActive,
    )

    private fun selected(profile: VehicleOpticalZoomProfile, bounds: ZoomBounds): Double {
        val remembered = magnifications[profile] ?: profile.defaultMagnification
        val finite = remembered.takeIf { it.isFinite() } ?: profile.defaultMagnification
        val clamped = finite.coerceIn(bounds.min, bounds.max)
        magnifications[profile] = clamped
        return clamped
    }

    private fun activeSession(player: Player): HoldSession? {
        val active = session ?: return null
        if (!contextMatches(active, player)
            || ModKeyMappings.VEHICLE_HOLD_ZOOM.key != active.pressedInput
            || notInGame
            || !ClientEventHandler.zoomVehicle
        ) {
            clearHold(active)
            return null
        }
        return active
    }

    private fun clearHold(active: HoldSession) {
        if (session !== active) return
        session = null
        if (!ClickEventHandler.switchZoom) {
            ClientEventHandler.zoomVehicle = false
        }
    }

    private fun contextMatches(active: HoldSession, player: Player?): Boolean {
        if (player == null || player.isSpectator || !player.isAlive || player.isRemoved) return false
        if (mc.level !== active.level || player.level() !== active.level || player.uuid != active.playerUuid) return false
        val context = resolveContext(player) ?: return false
        return context.vehicle === active.vehicle
                && context.vehicle.uuid == active.vehicleUuid
                && context.seatIndex == active.seatIndex
                && context.profile == active.profile
    }

    private fun resolveContext(player: Player): Context? {
        if (player.isSpectator || !player.isAlive || player.isRemoved) return null
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0 || !vehicle.banHand(player) || !vehicle.hasWeapon(seatIndex)) return null

        val profile = if (vehicle.vehicleType == VehicleType.HELICOPTER) {
            if (seatIndex == 0) {
                VehicleOpticalZoomProfile.HELICOPTER_PILOT
            } else {
                VehicleOpticalZoomProfile.HELICOPTER_GUNNER
            }
        } else {
            if (vehicle.vehicleType !in ARMORED_GROUND_TYPES) return null
            when {
                seatIndex == vehicle.turretControllerIndex -> VehicleOpticalZoomProfile.TANK_PRIMARY
                vehicle.isPassengerWeaponStationHeavyMachineGun(seatIndex, vehicle.getPrimaryWeaponIndex(seatIndex)) ->
                    VehicleOpticalZoomProfile.TANK_PASSENGER_HMG

                else -> null
            }
        } ?: return null
        val bounds = if (profile == VehicleOpticalZoomProfile.TANK_PRIMARY) {
            authoredGroundBounds(vehicle, profile.fallbackBounds())
        } else {
            profile.fallbackBounds()
        }
        return Context(vehicle, seatIndex, profile, bounds)
    }

    private fun VehicleOpticalZoomProfile.fallbackBounds() =
        ZoomBounds(minMagnification, maxMagnification)

    /** Reads only the generated typed range; malformed/missing values retain each role's safe default. */
    private fun authoredGroundBounds(vehicle: VehicleEntity, fallback: ZoomBounds): ZoomBounds {
        val range = runCatching { vehicle.computed().opticalZoomRange }.getOrNull() ?: return fallback
        val min = range.x.toDouble()
        val max = range.y.toDouble()
        return if (min.isFinite() && max.isFinite() && min > 0.0 && max > min) {
            ZoomBounds(min, max)
        } else {
            fallback
        }
    }

    private const val ZOOM_STEP = 0.5
    private val ARMORED_GROUND_TYPES = setOf(VehicleType.TANK, VehicleType.APC, VehicleType.AA)
}
