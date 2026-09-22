package com.atsuishio.superbwarfare.client.camera

import org.joml.Vector4d
import kotlin.math.cos
import kotlin.math.sin

/** Apply the chase's rotation delta to the authored external offset around the aircraft pivot. */
internal object FixedWingCameraOrbit {
    /** The alternate Euler branch is (yaw+180, 180-pitch): yaw delta stays, pitch delta reverses. */
    fun pitchOffsetForBranch(canonicalYaw: Float, viewYaw: Float, pitchOffset: Float): Float =
        if (kotlin.math.abs(Math.IEEEremainder((viewYaw - canonicalYaw).toDouble(), 360.0)) > 90.0)
            -pitchOffset else pitchOffset

    fun apply(
        position: Vector4d,
        pivotX: Double,
        pivotY: Double,
        pivotZ: Double,
        baseYaw: Float,
        yawOffset: Float,
        pitchOffset: Float,
    ): Boolean {
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() ||
            !pivotX.isFinite() || !pivotY.isFinite() || !pivotZ.isFinite() ||
            !baseYaw.isFinite() || !yawOffset.isFinite() || !pitchOffset.isFinite()) return false
        if (yawOffset == 0F && pitchOffset == 0F) return true

        // Minecraft forward is (-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch)).
        // R(new view) * inverse(R(base view)) = Ry(-newYaw) * Rx(deltaPitch) * Ry(baseYaw).
        // The existing authored offset (including bank and distance) is never reconstructed.
        val yaw = Math.toRadians(baseYaw.toDouble())
        val targetYaw = Math.toRadians(baseYaw.toDouble() + yawOffset)
        val pitch = Math.toRadians(pitchOffset.toDouble())
        val dx = position.x - pivotX
        val dy = position.y - pivotY
        val dz = position.z - pivotZ
        val localX = cos(yaw) * dx + sin(yaw) * dz
        val localZ = -sin(yaw) * dx + cos(yaw) * dz
        val pitchedY = cos(pitch) * dy - sin(pitch) * localZ
        val pitchedZ = sin(pitch) * dy + cos(pitch) * localZ
        val x = pivotX + cos(targetYaw) * localX - sin(targetYaw) * pitchedZ
        val y = pivotY + pitchedY
        val z = pivotZ + sin(targetYaw) * localX + cos(targetYaw) * pitchedZ
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        position.set(x, y, z, position.w)
        return true
    }
}
