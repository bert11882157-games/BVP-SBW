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
         * Swings a vehicle-local (y up, z forward) pair about the lateral hinge axis by [nozzleDegrees], nose-up
         * positive: an aft-pointing vector (0, -1) turns upward, a point behind the hinge rises. Writes [out] =
         * [y, z]. The afterburner renderer uses it so the flame, heat haze and TaP emitters follow the nozzle.
         */
        @JvmStatic
        fun swingPitch(y: Double, z: Double, nozzleDegrees: Double, out: DoubleArray) {
            val radians = nozzleDegrees * PI / 180.0
            val c = cos(radians)
            val s = sin(radians)
            out[0] = y * c - z * s
            out[1] = y * s + z * c
        }

        /**
         * Pitch-up authority left near a very high angle of attack, so vectoring cannot wind the nose into a
         * flat spin: full to [FULL_AUTHORITY_ALPHA], none from [NO_AUTHORITY_ALPHA]. Pitch-down is never limited.
         */
        const val FULL_AUTHORITY_ALPHA = 45.0
        const val NO_AUTHORITY_ALPHA = 70.0
        const val SURFACES_WORKING_AIRFLOW = 0.5

        @JvmStatic
        fun alphaAuthority(alphaDegrees: Double, nozzleDegrees: Double): Double =
            alphaAuthority(alphaDegrees, nozzleDegrees, 0.0, FULL_AUTHORITY_ALPHA)

        /**
         * As above, with the surfaces' own airflow authority [airflow] (0..1) scheduling the limit, as a flight control
         * system blends vectoring with the surfaces: with no airflow the push runs to [FULL_AUTHORITY_ALPHA] / [NO_AUTHORITY_ALPHA]
         * (the post-stall envelope), but where the surfaces work it fades out at the aircraft's stall angle
         * [stallAlphaDegrees] (none 12 degrees past it) so a full pull at speed cannot drive the nose deep into the stall.
         */
        @JvmStatic
        fun alphaAuthority(alphaDegrees: Double, nozzleDegrees: Double, airflow: Double, stallAlphaDegrees: Double): Double {
            if (!(alphaDegrees * nozzleDegrees > 0.0)) return 1.0
            // the surfaces count as working from half authority up
            val flow = if (airflow.isFinite()) (airflow / SURFACES_WORKING_AIRFLOW).coerceIn(0.0, 1.0) else 0.0
            val blend = flow * flow * (3.0 - 2.0 * flow)
            val full = FULL_AUTHORITY_ALPHA + (stallAlphaDegrees - FULL_AUTHORITY_ALPHA) * blend
            val none = NO_AUTHORITY_ALPHA + (stallAlphaDegrees + 12.0 - NO_AUTHORITY_ALPHA) * blend
            val t = ((abs(alphaDegrees) - full) / (none - full)).coerceIn(0.0, 1.0)
            return 1.0 - t * t * (3.0 - 2.0 * t)
        }
    }
}
