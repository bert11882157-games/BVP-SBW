package com.atsuishio.superbwarfare.client.aircraft

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

/** Earth-fixed aim point; only mouse input or a physical gimbal stop changes the anchor. */
internal class AircraftPodStabilizer {
    private var anchor: Vec3? = null
    private var cursorX = Double.NaN
    private var cursorY = Double.NaN

    fun reset() { anchor = null; resetCursor() }
    fun resetCursor() { cursorX = Double.NaN; cursorY = Double.NaN }
    fun begin(pod: AircraftPodView, hull: Matrix4d, origin: Vec3) {
        val local = preciseDirection(30.0.coerceIn(pod.pitchMin.toDouble(), pod.pitchMax.toDouble()), 0.0)
        val world = hull.transformDirection(Vector3d(local.x, local.y, local.z)).normalize()
        anchor = origin.add(Vec3(world.x, world.y, world.z).scale(pod.range))
        resetCursor()
    }
    fun designate(point: Vec3) { if (finite(point)) anchor = point }

    fun direction(pod: AircraftPodView, hull: Matrix4d, origin: Vec3): Vec3? {
        val target = anchor ?: return null
        val delta = target.subtract(origin)
        if (!finite(delta) || delta.lengthSqr() < 1e-8) return null
        val wanted = delta.normalize()
        val local = Matrix4d(hull).invert().transformDirection(Vector3d(wanted.x, wanted.y, wanted.z)).normalize()
        val yaw = Math.toDegrees(atan2(-local.x, local.z))
        val pitch = Math.toDegrees(atan2(-local.y, hypot(local.x, local.z)))
        val boundedYaw = yaw.coerceIn(-pod.yawLimit.toDouble(), pod.yawLimit.toDouble())
        val boundedPitch = pitch.coerceIn(pod.pitchMin.toDouble(), pod.pitchMax.toDouble())
        if (kotlin.math.abs(yaw - boundedYaw) < 1e-5 && kotlin.math.abs(pitch - boundedPitch) < 1e-5) return wanted
        val constrained = preciseDirection(boundedPitch, boundedYaw)
        val world = hull.transformDirection(Vector3d(constrained.x, constrained.y, constrained.z)).normalize()
        val result = Vec3(world.x, world.y, world.z)
        anchor = origin.add(result.scale(delta.length().coerceIn(1.0, pod.range)))
        return result
    }

    fun sample(x: Double, y: Double, sensitivity: Double, zoom: Double,
        pod: AircraftPodView, hull: Matrix4d, origin: Vec3) {
        if (!x.isFinite() || !y.isFinite() || !sensitivity.isFinite()) { resetCursor(); return }
        val direction = direction(pod, hull, origin) ?: return
        if (cursorX.isFinite() && cursorY.isFinite() && (x != cursorX || y != cursorY)) {
            val scale = sensitivity.coerceIn(0.01, 2.0) / zoom.coerceIn(1.0, pod.maxZoom)
            val yaw = Math.toDegrees(atan2(-direction.x, direction.z)) + (x - cursorX).coerceIn(-256.0, 256.0) * scale
            val pitch = (Math.toDegrees(atan2(-direction.y, hypot(direction.x, direction.z))) +
                (y - cursorY).coerceIn(-256.0, 256.0) * scale).coerceIn(-89.99, 89.99)
            val distance = anchor!!.distanceTo(origin).coerceIn(1.0, pod.range)
            anchor = origin.add(preciseDirection(pitch, yaw).scale(distance))
            direction(pod, hull, origin)
        }
        cursorX = x; cursorY = y
    }

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
    private fun preciseDirection(pitch: Double, yaw: Double): Vec3 {
        val p = Math.toRadians(pitch); val y = Math.toRadians(yaw)
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }
}
