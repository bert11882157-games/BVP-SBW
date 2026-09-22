package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.event.ClientMouseHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.notInGame
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec2
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * Client-only, edge-owned lifecycle for the held vehicle free-camera binding.
 *
 * This controller never mutates player/vehicle rotation. The ordinary mouse-turn path is frozen
 * while active, so authoritative aim keeps the exact orientation it had at entry. Camera rotation
 * is presentation-only and release restoration is consumed by CameraMixin without changing aim.
 */
object VehicleFreeCameraController {
    private data class Session(
        val playerUuid: UUID,
        val level: Any,
        val vehicle: VehicleEntity,
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val pressedInput: InputConstants.Key,
        val entryYaw: Float,
        val entryPitch: Float,
        val entryOffsetYaw: Double,
        val entryOffsetPitch: Double,
        var yaw: Float,
        var pitch: Float,
        var clientTicks: Int = 0,
    ) {
        val pitchBand = VehicleFreeCameraPitchBand(entryPitch)
    }

    private data class Restoration(
        val playerUuid: UUID,
        val level: Any,
        val vehicle: VehicleEntity,
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val yaw: Float,
        val pitch: Float,
        var clientTicks: Int = 0,
    )

    private var session: Session? = null
    private var restoration: Restoration? = null
    private var restorationObserved = false
    private var mouseResetPending = false
    private var releaseSequence = 0L

    /** Handles both keyboard and mouse edges. Repeats never toggle or restart the hold. */
    @JvmStatic
    fun handleInput(input: InputConstants.Key, action: Int) {
        when (action) {
            GLFW.GLFW_PRESS -> begin(input)
            GLFW.GLFW_RELEASE -> release(input)
        }
    }

    private fun begin(input: InputConstants.Key) {
        if (session != null || !ModKeyMappings.FREE_CAMERA.isActiveAndMatches(input)) return

        val player = mc.player ?: return
        val level = mc.level ?: return
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val seatIndex = vehicle.getSeatIndex(player)
        if (notInGame || player.isSpectator || !player.isAlive || player.isRemoved) return
        if (seatIndex < 0 || !vehicle.allowFreeCam()) return

        val camera = mc.gameRenderer.mainCamera
        if (!camera.yRot.isFinite() || !camera.xRot.isFinite()) return
        restoration = null
        restorationObserved = false
        session = Session(
            playerUuid = player.uuid,
            level = level,
            vehicle = vehicle,
            vehicleUuid = vehicle.uuid,
            seatIndex = seatIndex,
            pressedInput = input,
            entryYaw = camera.yRot,
            entryPitch = camera.xRot,
            entryOffsetYaw = ClientMouseHandler.freeCameraYaw,
            entryOffsetPitch = ClientMouseHandler.freeCameraPitch,
            yaw = camera.yRot,
            pitch = camera.xRot,
        )
        resetMouseAtEdge()
    }

    private fun release(input: InputConstants.Key) {
        val active = session ?: return
        if (input != active.pressedInput) return
        finish(active, restoreEntryView = contextMatches(active, mc.player) && !notInGame)
    }

    /**
     * Runs even when the local player is absent so GUI, focus, death, dismount, world and binding
     * transitions cannot leave a held state behind.
     */
    @JvmStatic
    fun tick(player: LocalPlayer?) {
        if (restorationObserved) {
            restoration = null
            restorationObserved = false
        } else {
            val saved = restoration
            if (saved != null) {
                val vehicle = player?.vehicle as? VehicleEntity
                saved.clientTicks++
                if (notInGame || !restorationMatches(saved, player, vehicle) || saved.clientTicks > 4) {
                    restoration = null
                }
            }
        }

        val active = session ?: return
        active.clientTicks++

        val contextMatches = contextMatches(active, player)
        val bindingMatches = ModKeyMappings.FREE_CAMERA.key == active.pressedInput
        val physicalHold = active.clientTicks <= 1 || ModKeyMappings.FREE_CAMERA.isDown
        if (!contextMatches || !bindingMatches || !physicalHold || notInGame) {
            finish(active, restoreEntryView = contextMatches && !notInGame)
        }
    }

    private fun contextMatches(active: Session, player: Player?): Boolean {
        if (player == null || player.isSpectator || !player.isAlive || player.isRemoved) return false
        if (mc.level !== active.level || player.level() !== active.level) return false
        if (player.uuid != active.playerUuid) return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        if (vehicle !== active.vehicle || vehicle.uuid != active.vehicleUuid || !vehicle.allowFreeCam()) return false
        return vehicle.getSeatIndex(player) == active.seatIndex
    }

    private fun restorationMatches(saved: Restoration, player: Player?, vehicle: VehicleEntity?): Boolean {
        if (player == null || vehicle == null || mc.level !== saved.level || player.level() !== saved.level) return false
        if (player.uuid != saved.playerUuid || vehicle !== saved.vehicle || vehicle.uuid != saved.vehicleUuid) return false
        return player.vehicle === vehicle && vehicle.getSeatIndex(player) == saved.seatIndex
    }

    private fun finish(active: Session, restoreEntryView: Boolean) {
        if (session !== active) return
        session = null
        val bodyCamera = aircraftBodyReturn(active.vehicle.vehicleType)
        ClientMouseHandler.freeCameraYaw = if (bodyCamera) 0.0 else active.entryOffsetYaw
        ClientMouseHandler.freeCameraPitch = if (bodyCamera) 0.0 else active.entryOffsetPitch
        if (bodyCamera) {
            if (restoreEntryView) FixedWingDynamicCamera.onFreeCameraReleased(active.vehicle)
            else FixedWingDynamicCamera.reset()
            VehicleCameraResolver.updateFreeCameraSession(active.playerUuid, false)
        }
        // Aircraft resume their current body, never an entry-world view captured before a turn.
        // Fixed-wing chase additionally waits for the release drag to finish; aim is untouched.
        restoration = if (restoreEntryView && !bodyCamera) {
            Restoration(
                active.playerUuid,
                active.level,
                active.vehicle,
                active.vehicleUuid,
                active.seatIndex,
                active.entryYaw,
                active.entryPitch,
            )
        } else {
            null
        }
        restorationObserved = false
        releaseSequence++
        resetMouseAtEdge()
    }

    private fun resetMouseAtEdge() {
        // Key callbacks may run before a render with no intervening END tick. Clear the sampled
        // inertia now as well as rebasing at END; neither side may replay the held gesture.
        ClientMouseHandler.resetFreeCameraSampling()
        mouseResetPending = true
    }

    /** Scalar-only opt-in probe seam; no event logging or per-frame diagnostic allocation. */
    @JvmStatic fun getReleaseSequence(): Long = releaseSequence

    /** Adds presentation-only view rotation while retaining legacy free-camera transform offsets. */
    @JvmStatic
    fun applyMouseDelta(yawDegrees: Float, pitchDegrees: Float) {
        val active = session ?: return
        active.yaw = Mth.wrapDegrees(active.yaw + yawDegrees)
        val acceptedPitch = active.pitchBand.clamp(active.pitch + pitchDegrees)
        val acceptedPitchDelta = acceptedPitch - active.pitch
        active.pitch = acceptedPitch
        ClientMouseHandler.freeCameraYaw = Mth.wrapDegrees(ClientMouseHandler.freeCameraYaw - yawDegrees)
        ClientMouseHandler.freeCameraPitch =
            Mth.wrapDegrees(ClientMouseHandler.freeCameraPitch + acceptedPitchDelta)
    }

    @JvmStatic
    fun isActive(): Boolean = session != null

    @JvmStatic
    fun isActive(player: Player): Boolean {
        val active = session ?: return false
        return contextMatches(active, player)
    }

    @JvmStatic
    fun hasPresentation(player: Player?, vehicle: VehicleEntity?): Boolean {
        val active = session
        if (active != null && player != null && vehicle != null && contextMatches(active, player)) return true
        return restoration?.let { restorationMatches(it, player, vehicle) } == true
    }

    /** Called by CameraMixin; restoration remains stable for every camera query in the same frame. */
    @JvmStatic
    fun rotationOverride(player: Player?, vehicle: VehicleEntity?): Vec2? {
        val active = session
        if (active != null && player != null && vehicle != null && contextMatches(active, player)) {
            return Vec2(active.yaw, active.pitch)
        }
        val saved = restoration ?: return null
        if (!restorationMatches(saved, player, vehicle)) return null
        restorationObserved = true
        return Vec2(saved.yaw, saved.pitch)
    }

    @JvmStatic
    fun consumeMouseReset(): Boolean {
        val pending = mouseResetPending
        mouseResetPending = false
        return pending
    }
}

/** Capture the visible Euler branch once; canonicalizing it would also flip camera-up. */
internal class VehicleFreeCameraPitchBand(entryPitch: Float) {
    private val center = if (kotlin.math.abs(entryPitch) <= 90f) 0f
        else 180f * kotlin.math.round(entryPitch / 180f)

    fun clamp(proposedPitch: Float): Float = proposedPitch.coerceIn(center - 90f, center + 90f)
}

internal fun aircraftBodyReturn(type: VehicleType?): Boolean =
    type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER
