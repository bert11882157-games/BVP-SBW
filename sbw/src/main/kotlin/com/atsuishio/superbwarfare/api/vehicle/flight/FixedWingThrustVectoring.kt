package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Opt-in pitch thrust vectoring (per-vehicle data, flight_reference engineering.thrustVectoring).
 *
 * The nozzle follows the actuated elevator, so the angle used by the physics and by the visual rig is one
 * value: [nozzleAngleDegrees] of the model's own rate-limited elevator. Positive is nose-up, which turns the
 * exhaust upward; the thrust force on the airframe then points down at the tail, adds a nose-up moment of
 * thrust x sin(angle) x [armMetres], a downward force component and a forward-thrust loss of 1 - cos(angle).
 *
 * Lengths are full scale (the same figures the aircraft data uses); [pitchAccelerationRadPerS2] takes a full-scale
 * thrust acceleration, so the world length scale cancels out of the angular result.
 */
data class FixedWingThrustVectoring(
    /** Largest nozzle deflection either way at full elevator. */
    val pitchDegrees: Double,
    /** Distance from the centre of gravity to the nozzle pivot, along the fuselage. */
    val armMetres: Double,
    /** Pitch radius of gyration: inertia per unit mass is its square. */
    val gyrationRadiusMetres: Double,
) {
    init {
        require(pitchDegrees.isFinite() && pitchDegrees > 0.0 && pitchDegrees <= MAX_PITCH_DEGREES)
        require(armMetres.isFinite() && armMetres > 0.0 && armMetres <= 40.0)
        require(gyrationRadiusMetres.isFinite() && gyrationRadiusMetres >= 0.5 && gyrationRadiusMetres <= 40.0)
    }

    /** Nozzle angle for a normalized actuated elevator (-1..1). Nose-up elevator = exhaust up = positive. */
    fun nozzleAngleDegrees(elevator: Double): Double =
        if (elevator.isFinite()) elevator.coerceIn(-1.0, 1.0) * pitchDegrees else 0.0

    /** Nose-up angular acceleration (rad/s^2) from a full-scale thrust acceleration (m/s^2) at a nozzle angle. */
    fun pitchAccelerationRadPerS2(thrustAccelerationMps2: Double, nozzleDegrees: Double): Double =
        thrustAccelerationMps2 * sin(nozzleDegrees * PI / 180.0) * armMetres /
            (gyrationRadiusMetres * gyrationRadiusMetres)

    companion object {
        const val MAX_PITCH_DEGREES = 45.0

        /** Thrust direction in body axes: [forward, up] for a nozzle angle (exhaust up = force down). */
        @JvmStatic
        fun thrustDirection(nozzleDegrees: Double): DoubleArray {
            val radians = nozzleDegrees * PI / 180.0
            return doubleArrayOf(cos(radians), -sin(radians))
        }

        /**
         * Pitch-up authority left near a very high angle of attack, so vectoring cannot wind the nose into a
         * flat spin: full to [FULL_AUTHORITY_ALPHA], none from [NO_AUTHORITY_ALPHA]. Pitch-down is never limited.
         */
        const val FULL_AUTHORITY_ALPHA = 45.0
        const val NO_AUTHORITY_ALPHA = 70.0

        @JvmStatic
        fun alphaAuthority(alphaDegrees: Double, nozzleDegrees: Double): Double {
            if (!(alphaDegrees * nozzleDegrees > 0.0)) return 1.0
            val t = ((abs(alphaDegrees) - FULL_AUTHORITY_ALPHA) / (NO_AUTHORITY_ALPHA - FULL_AUTHORITY_ALPHA))
                .coerceIn(0.0, 1.0)
            return 1.0 - t * t * (3.0 - 2.0 * t)
        }
    }
}
