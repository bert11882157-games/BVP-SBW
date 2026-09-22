package com.atsuishio.superbwarfare.api.vehicle.aim

import net.minecraft.world.phys.Vec3

/**
 * One validated center-screen ray used by the server-side geometric-zero calculation.
 *
 * The ray is deliberately a presentation/input sample only: it carries no target, hit,
 * camera-write, or projectile authority.  The direction is normalized at construction so
 * callers cannot accidentally make the selected zero depend on input-vector magnitude.
 */
data class VehicleAimCameraRay private constructor(
    val origin: Vec3,
    val direction: Vec3,
) {
    fun pointAt(distanceBlocks: Double): Vec3 = origin.add(direction.scale(distanceBlocks))

    companion object {
        private const val MIN_DIRECTION_LENGTH_SQR = 1.0E-12

        @JvmStatic
        fun validated(origin: Vec3?, direction: Vec3?): VehicleAimCameraRay? {
            if (origin == null || direction == null ||
                !isFinite(origin) || !isFinite(direction)
            ) return null
            val lengthSqr = direction.lengthSqr()
            if (!lengthSqr.isFinite() || lengthSqr <= MIN_DIRECTION_LENGTH_SQR) return null
            return VehicleAimCameraRay(origin, direction.normalize())
        }

        private fun isFinite(value: Vec3): Boolean =
            value.x.isFinite() && value.y.isFinite() && value.z.isFinite()
    }
}
