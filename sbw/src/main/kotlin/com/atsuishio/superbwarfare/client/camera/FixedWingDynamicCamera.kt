package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.config.client.PlaneControlConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec2

/** One render-owned chase for cockpit and external views; authored camera positions are untouched. */
object FixedWingDynamicCamera {
    private var vehicle: VehicleEntity? = null
    private var cameraType: net.minecraft.client.CameraType? = null
    private val motion = FixedWingCameraMotion()
    private val returnState = FixedWingCameraReturnState()
    private var observedTick = Long.MIN_VALUE

    @JvmStatic fun getStrength(): Double = PlaneControlConfig.DYNAMIC_CAMERA_STRENGTH.get().coerceIn(0.0, 1.0)

    @JvmStatic fun setStrength(value: Double) {
        if (!value.isFinite()) return
        PlaneControlConfig.DYNAMIC_CAMERA_STRENGTH.set(value.coerceIn(0.0, 1.0))
        PlaneControlConfig.DYNAMIC_CAMERA_STRENGTH.save()
    }

    private fun eligible(candidate: VehicleEntity): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        return player.isAlive && !player.isSpectator &&
            player.vehicle === candidate && candidate.level() === player.level() &&
            candidate.isFixedWingFlightVehicle() && candidate.getSeatIndex(player) == 0 &&
            !candidate.isRemoved && !candidate.isWreck && mc.screen == null && mc.isWindowActive &&
            mc.mouseHandler.isMouseGrabbed && !VehicleFreeCameraController.hasPresentation(player, candidate)
    }

    fun observe(candidate: VehicleEntity, frame: Long, nanos: Long, partialTick: Float,
                target: FixedWingPilotIntent?, manual: Boolean) {
        if (!eligible(candidate)) { reset(); return }
        val type = Minecraft.getInstance().options.cameraType
        if (vehicle !== candidate || cameraType != type) { reset(); vehicle = candidate; cameraType = type }
        observedTick = candidate.level().gameTime
        if (!partialTick.isFinite()) { reset(); return }
        if (returnState.isBodyCentered) {
            // The input sampler rebases on release. Finish one returned frame before a later
            // accepted displacement can resume chase; continued fresh steering needs no pause.
            returnState.observeBodyFrame(frame, nanos)
            return
        }
        motion.advance(frame, nanos, target, candidate.getResolvedChassisYaw(partialTick).toDouble(),
            candidate.getPitch(partialTick).toDouble(), getStrength(), manual)
    }

    fun offset(candidate: VehicleEntity, partialTick: Float): Vec2 {
        if (vehicle !== candidate || cameraType != Minecraft.getInstance().options.cameraType ||
            !eligible(candidate) || !partialTick.isFinite() || getStrength() == 0.0 ||
            returnState.isBodyCentered || candidate.level().gameTime - observedTick !in 0L..2L) return Vec2.ZERO
        val (yaw, pitch) = motion.sample()
        return Vec2(yaw.toFloat(), pitch.toFloat())
    }

    fun reset() {
        vehicle = null
        cameraType = null
        motion.reset()
        observedTick = Long.MIN_VALUE
        // Sampler/view resets also run on release. They must not rearm a retained old target.
        val mc = Minecraft.getInstance()
        val player = mc.player?.takeIf { it.isAlive && !it.isSpectator }
        val mounted = (player?.vehicle as? VehicleEntity)?.takeUnless { it.isRemoved || it.isWreck }
        val seat = if (player != null && mounted != null) mounted.getSeatIndex(player) else -1
        returnState.retainContext(player, mounted, mc.level, seat)
    }

    @JvmStatic fun onFreeCameraReleased(candidate: VehicleEntity) {
        reset()
        if (!eligible(candidate)) return
        val player = Minecraft.getInstance().player ?: return
        returnState.hold(player, candidate, candidate.level(), candidate.getSeatIndex(player))
    }

    /** Called only for a newly consumed physical mouse gesture, never for aim/camera interpolation. */
    @JvmStatic fun rearmFromMouseGesture(candidate: VehicleEntity, frame: Long, nanos: Long) {
        if (!eligible(candidate)) return
        val player = Minecraft.getInstance().player ?: return
        returnState.retainContext(player, candidate, candidate.level(), candidate.getSeatIndex(player))
        if (returnState.rearm(frame, nanos)) motion.reset()
    }

    @JvmStatic fun getReturnPhase(): String = returnState.phase
    @JvmStatic fun getSuppressedReturnGestures(): Long = returnState.suppressedGestures
    @JvmStatic fun getReturnRearms(): Long = returnState.rearms
}

/** Identity-bound return ownership survives motion resets, not a changed passenger/world/seat. */
internal class FixedWingCameraReturnState {
    private var player: Any? = null
    private var vehicle: Any? = null
    private var level: Any? = null
    private var seat = -1
    private var bodyFrame = Long.MIN_VALUE
    private var bodyFrameNanos = Long.MIN_VALUE
    var suppressedGestures = 0L
        private set
    var rearms = 0L
        private set
    val isBodyCentered: Boolean get() = vehicle != null
    val phase: String get() = if (!isBodyCentered) "CHASE" else if (bodyFrame != Long.MIN_VALUE) "RETURN_READY" else "RETURN_FENCE"

    fun hold(player: Any, vehicle: Any, level: Any, seat: Int) {
        this.player = player; this.vehicle = vehicle; this.level = level; this.seat = seat
        bodyFrame = Long.MIN_VALUE; bodyFrameNanos = Long.MIN_VALUE
    }

    fun observeBodyFrame(frame: Long, nanos: Long) {
        if (!isBodyCentered || frame < 0 || frame <= bodyFrame) return
        bodyFrame = frame; bodyFrameNanos = nanos
    }

    fun retainContext(player: Any?, vehicle: Any?, level: Any?, seat: Int) {
        if (this.player !== player || this.vehicle !== vehicle || this.level !== level || this.seat != seat) clear()
    }

    fun rearm(frame: Long, nanos: Long): Boolean {
        if (!isBodyCentered) return false
        if (bodyFrame == Long.MIN_VALUE || frame <= bodyFrame ||
            nanos - bodyFrameNanos !in 0L..MAX_OBSERVATION_GAP_NANOS) {
            // Reject a release-frame sample, replay, or unobserved stall. The next completed
            // frame establishes a new baseline without waiting for mouse quiescence.
            suppressedGestures++
            return false
        }
        rearms++
        clear()
        return true
    }

    private fun clear() {
        player = null; vehicle = null; level = null; seat = -1
        bodyFrame = Long.MIN_VALUE; bodyFrameNanos = Long.MIN_VALUE
    }

    private companion object {
        const val MAX_OBSERVATION_GAP_NANOS = 250_000_000L
    }
}
