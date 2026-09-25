package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.sin

/** Server-owned loss of lift and control, synchronized by the ordinary flight receipt. */
class AircraftWreckFlightStrategy : VehicleFlightStrategy() {
    private var ticks = 0
    private var initialPitch = 0.0
    private var initialRoll = 0.0
    override fun onActivated(vehicle: VehicleEntity) { ticks = 0 }
    override fun tickServer(vehicle: VehicleEntity, input: VehicleFlightInputContext): VehicleFlightTickResult {
        if (ticks == 0) {
            initialPitch = input.bodyPitchDegrees
            initialRoll = input.bodyRollDegrees
        }
        val fixedWing = vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy
        val gravity = gravityPerTick(fixedWing?.handling?.gravityMps2, input.gravityPerTick)
            .let { if (AircraftWreckBreakup.mask(vehicle) != 0) it.coerceAtLeast(9.80665 / 400.0) else it }
        AircraftFuselageWreck.step(vehicle, input, gravity)?.let { return it }
        // A fragmenting hull keeps sliding into its first fuselage contact; that contact records the
        // breakup momentum, so a zero here would launch every fragment at rest.
        return step(input.copy(gravityPerTick = gravity), ticks++, if (vehicle.uuid.leastSignificantBits and 1L == 0L) 1 else -1,
            vehicle.sympatheticDetonated, initialPitch, initialRoll, vehicle.uuid.leastSignificantBits,
            AircraftWreckBreakup.mask(vehicle), AircraftFuselageWreck.fragmented(vehicle))
    }

    companion object {
        // Preserve the later gentle airborne momentum decay.
        internal const val MOMENTUM_RETENTION = 0.9995
        internal fun gravityPerTick(fixedWingGravityMps2: Double?, legacyPerTick: Double): Double =
            (fixedWingGravityMps2?.div(20.0 * 20.0) ?: legacyPerTick).coerceAtLeast(0.0)
        private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

        /** Far copies already synchronize pitch, so both render paths agree without a new timer packet. */
        internal fun noseDownIntensity(pitchDegrees: Double): Double =
            if (pitchDegrees.isFinite()) smooth((Mth.wrapDegrees(pitchDegrees) - 15.0) / 60.0) else 0.0

        internal fun step(input: VehicleFlightInputContext, ticks: Int, direction: Int, impacted: Boolean,
                          initialPitch: Double = input.bodyPitchDegrees, initialRoll: Double = input.bodyRollDegrees,
                          seed: Long = 0L, detachedWings: Int = 0,
                          slideOnGround: Boolean = false): VehicleFlightTickResult {
            // A living, sheared aircraft must still slide/roll into its fuselage contact.
            // A wheel touching first is not an impact event and cannot erase its inertia.
            val grounded = !input.inFluid && (impacted || (input.onGround && input.wreck))
            // Decay world-space momentum continuously; spinning does not steer the wreck in circles.
            val horizontalRetention = when {
                input.inFluid -> .92
                detachedWings == 3 -> .990
                detachedWings != 0 -> .992
                else -> MOMENTUM_RETENTION
            }
            // Torn surfaces bleed horizontal speed while gravity keeps accelerating the fall.
            // Keep the first-frame inertia; never replace world motion with facing-direction thrust.
            val verticalRetention = if (input.inFluid) .92 else MOMENTUM_RETENTION
            val motion = if (grounded) {
                if (slideOnGround) AircraftFuselageWreck.hullMotion(input.previousMotion, true, false, input.gravityPerTick)
                else Vec3.ZERO
            } else Vec3(
                input.previousMotion.x * horizontalRetention,
                input.previousMotion.y * verticalRetention - input.gravityPerTick.coerceAtLeast(if (input.inFluid) .018 else 0.0),
                input.previousMotion.z * horizontalRetention)
            // Coast for 1.5 seconds, lose control progressively, then enter the dive over 4.25 seconds.
            // UUID phases vary each wreck without client randomness or abrupt per-tick impulses.
            val instability = smooth((ticks - if (detachedWings != 0) 3.0 else 30.0) / 45.0)
            val dive = if (detachedWings == 3) smooth((ticks - 10.0) / 65.0)
                else if (detachedWings != 0) smooth((ticks - 30.0) / 85.0)
                else smooth((ticks - 95.0) / 85.0)
            val phase = (seed and 65535L).toDouble() / 65536.0 * Math.PI * 2
            val secondPhase = ((seed ushr 16) and 65535L).toDouble() / 65536.0 * Math.PI * 2
            val handedness = direction.coerceIn(-1, 1)
            val spin = handedness * (1.2 + sin(ticks * .047 + phase) * 1.7 +
                sin(ticks * .083 + secondPhase) * .8) * instability * (1 - dive * .65)
            val wanderingPitch = 12.0 + sin(ticks * .043 + secondPhase) * 9.0
            val coastPitch = initialPitch + Mth.wrapDegrees(wanderingPitch - initialPitch) * instability
            val targetPitch = coastPitch + Mth.wrapDegrees(82.0 - coastPitch) * dive
            val pitch = if (grounded) input.bodyPitchDegrees else input.bodyPitchDegrees +
                Mth.wrapDegrees(targetPitch - input.bodyPitchDegrees).coerceIn(-1.8, 1.8)
            val wanderingRoll = initialRoll + sin(ticks * .039 + phase) * 60.0 +
                sin(ticks * .071 + secondPhase) * 38.0
            val flatRock = Mth.wrapDegrees(wanderingRoll - input.bodyRollDegrees).coerceIn(-3.0, 3.0) * instability
            val wingRoll = when (detachedWings) {
                // Hull negative X is left; positive logical Z rotation lowers that side.
                AircraftWreckBreakup.LEFT -> 1.0
                AircraftWreckBreakup.RIGHT -> -1.0
                else -> 0.0
            }
            val rollStep = if (wingRoll != 0.0) wingRoll *
                (1.0 + 5.0 * smooth(ticks / 25.0)) + sin(ticks * .17 + phase) * .4
                else flatRock * (1 - dive) + handedness * 2.5 * dive
            val roll = if (grounded) input.bodyRollDegrees else Mth.wrapDegrees(input.bodyRollDegrees + rollStep)
            return VehicleFlightTickResult(motion, 0.0, 0.0, 0.0, 0.0,
                Mth.wrapDegrees(input.bodyYawDegrees + if (grounded) 0.0 else spin).toFloat(),
                pitch.toFloat(), roll.toFloat(), true)
        }
    }
}
