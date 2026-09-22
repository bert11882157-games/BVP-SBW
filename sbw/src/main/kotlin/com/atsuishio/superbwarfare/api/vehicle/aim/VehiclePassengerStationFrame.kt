package com.atsuishio.superbwarfare.api.vehicle.aim

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d

/** Native station composition, shared by authority and immutable presentation. Angles are degrees. */
internal object VehiclePassengerStationFrame {
    @JvmStatic
    fun base(hull: Matrix4d, yawPivot: Vec3, baseYawDegrees: Float): Matrix4d =
        Matrix4d(hull).translate(yawPivot.x, yawPivot.y, yawPivot.z)
            .rotateY(Math.toRadians(baseYawDegrees.toDouble()))

    @JvmStatic
    fun angles(base: Matrix4d, worldDirection: Vec3): VehicleAimMath.DirectionAngles? {
        if (!worldDirection.x.isFinite() || !worldDirection.y.isFinite() ||
            !worldDirection.z.isFinite() || worldDirection.lengthSqr() <= 1.0E-12
        ) return null
        val local = Matrix4d(base).invert().transformDirection(
            Vector3d(worldDirection.x, worldDirection.y, worldDirection.z),
        )
        if (!local.x.isFinite() || !local.y.isFinite() || !local.z.isFinite() ||
            local.lengthSquared() <= 1.0E-12
        ) return null
        return VehicleAimMath.directionAngles(local.x, local.y, local.z)
    }
}
