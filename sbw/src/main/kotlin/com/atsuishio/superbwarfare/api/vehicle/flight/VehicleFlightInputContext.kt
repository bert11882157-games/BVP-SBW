package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3

/** Immutable finite server input, including accepted mouse axes, sampled immediately before strategy travel. */
data class VehicleFlightInputContext(
    val serverTick: Long,
    val rawInputBits: Short,
    val mouseInputX: Double,
    val mouseInputY: Double,
    val previousMotion: Vec3,
    val requestedMotion: Vec3,
    val lookDirection: Vec3,
    val upDirection: Vec3,
    val enginePower: Double,
    val occupied: Boolean,
    val wreck: Boolean,
    val hoverMode: Boolean,
    val gravityPerTick: Double,
    /** World-space velocity relative to the local air mass.  SBW currently has no wind field, so
     * the authoritative requested motion is the air-relative sample. */
    val airVelocity: Vec3 = requestedMotion,
    /** Normalized pilot controls.  Legacy strategies ignore these optional fixed-wing channels. */
    val throttleInput: Double = 0.0,
    val pitchInput: Double = 0.0,
    val rollInput: Double = 0.0,
    val yawInput: Double = 0.0,
    val afterburnerRequested: Boolean = false,
    val brakeRequested: Boolean = false,
    val onGround: Boolean = false,
    val inFluid: Boolean = false,
    val bodyYawDegrees: Double = 0.0,
    val bodyPitchDegrees: Double = 0.0,
    val bodyRollDegrees: Double = 0.0,
    /** Signed held fixed-wing throttle axis; neutral preserves the current spool. */
    val fixedWingThrottleAxis: Double = 0.0,
    /** Per-sample pilot mouse pitch delta; fixed-wing strategy integrates it into a bounded stick. */
    val fixedWingMousePitchDelta: Double = 0.0,
    /** Per-sample pilot mouse roll delta; fixed-wing strategy integrates it into a bounded stick. */
    val fixedWingMouseRollDelta: Double = 0.0,
    /** Signed held A/D taxi-rudder axis; airborne roll consumes [rollInput] instead. */
    val fixedWingRudderInput: Double = 0.0,
    val fixedWingAfterburnerRequested: Boolean = false,
    val fixedWingAirbrakeRequested: Boolean = false,
    /** Explicitly centers the fixed-wing virtual stick faster than its automatic return. */
    val fixedWingRecenterRequested: Boolean = false,
) {
    init {
        require(mouseInputX.isFinite()) { "mouseInputX must be finite" }
        require(mouseInputY.isFinite()) { "mouseInputY must be finite" }
        require(previousMotion.hasFiniteComponents()) { "previousMotion must be finite" }
        require(requestedMotion.hasFiniteComponents()) { "requestedMotion must be finite" }
        require(airVelocity.hasFiniteComponents()) { "airVelocity must be finite" }
        require(lookDirection.hasFiniteComponents()) { "lookDirection must be finite" }
        require(upDirection.hasFiniteComponents()) { "upDirection must be finite" }
        require(enginePower.isFinite()) { "enginePower must be finite" }
        require(gravityPerTick.isFinite()) { "gravityPerTick must be finite" }
        require(throttleInput.isFinite()) { "throttleInput must be finite" }
        require(pitchInput.isFinite()) { "pitchInput must be finite" }
        require(rollInput.isFinite()) { "rollInput must be finite" }
        require(yawInput.isFinite()) { "yawInput must be finite" }
        require(bodyYawDegrees.isFinite()) { "bodyYawDegrees must be finite" }
        require(bodyPitchDegrees.isFinite()) { "bodyPitchDegrees must be finite" }
        require(bodyRollDegrees.isFinite()) { "bodyRollDegrees must be finite" }
        require(fixedWingThrottleAxis.isFinite() && fixedWingThrottleAxis in -1.0..1.0) {
            "fixedWingThrottleAxis must be finite in [-1,1]"
        }
        require(fixedWingMousePitchDelta.isFinite()) { "fixedWingMousePitchDelta must be finite" }
        require(fixedWingMouseRollDelta.isFinite()) { "fixedWingMouseRollDelta must be finite" }
        require(fixedWingRudderInput.isFinite() && fixedWingRudderInput in -1.0..1.0) {
            "fixedWingRudderInput must be finite in [-1,1]"
        }
    }
}

private fun Vec3.hasFiniteComponents(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
