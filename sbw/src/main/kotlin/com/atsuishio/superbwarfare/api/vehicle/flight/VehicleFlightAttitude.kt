package com.atsuishio.superbwarfare.api.vehicle.flight

import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** Whole-body interpolation in the native Ry(-yaw) Rx(pitch) Rz(roll) frame. */
object VehicleFlightAttitude {
    data class Angles(val yaw: Float, val pitch: Float, val roll: Float)

    fun wrap(angle: Float): Float {
        if (!angle.isFinite()) return 0F
        val reduced = angle % 360F
        return when {
            reduced >= 180F -> reduced - 360F
            reduced < -180F -> reduced + 360F
            else -> reduced
        }
    }

    fun alignedPrevious(previous: Float, current: Float): Float = current - wrap(current - previous)

    fun quaternion(yaw: Float, pitch: Float, roll: Float): Quaterniond = Quaterniond()
        .rotateY(Math.toRadians(-wrap(yaw).toDouble()))
        .rotateX(Math.toRadians(wrap(pitch).toDouble()))
        .rotateZ(Math.toRadians(wrap(roll).toDouble()))

    /** Physical separation, including equivalent Euler representations at pitch poles. */
    fun separationDegrees(yaw0: Float, pitch0: Float, roll0: Float,
                          yaw1: Float, pitch1: Float, roll1: Float): Double {
        if (!yaw0.isFinite() || !pitch0.isFinite() || !roll0.isFinite() ||
            !yaw1.isFinite() || !pitch1.isFinite() || !roll1.isFinite()) return Double.POSITIVE_INFINITY
        val dot = quaternion(yaw0, pitch0, roll0).dot(quaternion(yaw1, pitch1, roll1))
        return Math.toDegrees(2.0 * acos(abs(dot).coerceIn(0.0, 1.0)))
    }

    fun interpolate(yaw0: Float, pitch0: Float, roll0: Float,
                    yaw1: Float, pitch1: Float, roll1: Float, partial: Float): Angles {
        val t = if (partial.isFinite()) partial.coerceIn(0F, 1F).toDouble() else 1.0
        if (t == 0.0) return Angles(wrap(yaw0), wrap(pitch0), wrap(roll0))
        if (t == 1.0) return Angles(wrap(yaw1), wrap(pitch1), wrap(roll1))
        val q = quaternion(yaw0, pitch0, roll0).slerp(quaternion(yaw1, pitch1, roll1), t).normalize()
        val forward = q.transform(Vector3d(0.0, 0.0, 1.0))
        val pitch = -asin(forward.y.coerceIn(-1.0, 1.0))
        val yaw: Double
        val roll: Double
        if (hypot(forward.x, forward.z) > 1.0e-6) {
            yaw = atan2(-forward.x, forward.z)
            val right = q.transform(Vector3d(1.0, 0.0, 0.0))
            val up = q.transform(Vector3d(0.0, 1.0, 0.0))
            roll = atan2(right.y, up.y)
        } else {
            // Heading alone is undefined at vertical pitch. Choose a continuous reference,
            // then recover the coupled roll from the remaining rotation, preserving the pose.
            yaw = Math.toRadians(yaw0 + wrap(yaw1 - yaw0) * t)
            val residual = Quaterniond().rotateX(-pitch).rotateY(yaw).mul(q).normalize()
            roll = 2.0 * atan2(residual.z, residual.w)
        }
        return Angles(wrap(Math.toDegrees(yaw).toFloat()), Math.toDegrees(pitch).toFloat(),
            wrap(Math.toDegrees(roll).toFloat()))
    }

    /** All render consumers of one entity/partial share the same reconstructed Euler triplet. */
    class Cache {
        private val inputs = FloatArray(7) { Float.NaN }
        private var value = Angles(0F, 0F, 0F)
        fun sample(yaw0: Float, pitch0: Float, roll0: Float,
                   yaw1: Float, pitch1: Float, roll1: Float, partial: Float): Angles {
            if (inputs[0] != yaw0 || inputs[1] != pitch0 || inputs[2] != roll0 ||
                inputs[3] != yaw1 || inputs[4] != pitch1 || inputs[5] != roll1 || inputs[6] != partial) {
                value = interpolate(yaw0, pitch0, roll0, yaw1, pitch1, roll1, partial)
                inputs[0] = yaw0; inputs[1] = pitch0; inputs[2] = roll0
                inputs[3] = yaw1; inputs[4] = pitch1; inputs[5] = roll1; inputs[6] = partial
            }
            return value
        }
    }
}
