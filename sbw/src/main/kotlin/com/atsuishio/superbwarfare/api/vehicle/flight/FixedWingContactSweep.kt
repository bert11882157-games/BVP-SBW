package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Continuous translation of an authored oriented box against one block collision box. */
internal object FixedWingContactSweep {
    data class Contact(
        val fraction: Double, val normal: Vec3, val initiallyOverlapping: Boolean,
        val penetrationDepth: Double = 0.0,
    )
    private data class Axis(val x: Double, val y: Double, val z: Double, val radius: Double)

    class Body(box: OBB) {
        private val center = Vector3d(box.center)
        private val basis = box.getAxes()
        private val extents = Vector3d(box.extents)
        private val axes: List<Axis>
        val bounds: AABB

        init {
            require(center.isFinite && extents.isFinite && extents.x >= 0 && extents.y >= 0 && extents.z >= 0)
            require(basis.all { it.isFinite && abs(it.lengthSquared() - 1.0) < 1e-6 })
            val world = arrayOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))
            val candidates = ArrayList<Vector3d>(15)
            candidates.addAll(world); candidates.addAll(basis)
            for (local in basis) for (fixed in world) candidates.add(local.cross(fixed, Vector3d()))
            axes = candidates.filter { it.lengthSquared() > 1e-12 }.map {
                it.mul(1.0 / sqrt(it.lengthSquared()))
                Axis(it.x, it.y, it.z, abs(it.dot(basis[0])) * extents.x +
                    abs(it.dot(basis[1])) * extents.y + abs(it.dot(basis[2])) * extents.z)
            }
            val x = abs(basis[0].x) * extents.x + abs(basis[1].x) * extents.y + abs(basis[2].x) * extents.z
            val y = abs(basis[0].y) * extents.x + abs(basis[1].y) * extents.y + abs(basis[2].y) * extents.z
            val z = abs(basis[0].z) * extents.x + abs(basis[1].z) * extents.y + abs(basis[2].z) * extents.z
            bounds = AABB(center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z)
        }

        fun sweep(displacement: Vec3, obstacle: AABB): Contact? {
            require(displacement.x.isFinite() && displacement.y.isFinite() && displacement.z.isFinite())
            val target = obstacle.center
            val halfX = obstacle.xsize * 0.5; val halfY = obstacle.ysize * 0.5; val halfZ = obstacle.zsize * 0.5
            val rx = center.x - target.x; val ry = center.y - target.y; val rz = center.z - target.z
            var entry = 0.0; var exit = 1.0
            var entryNormal = Vec3.ZERO
            var minimumPenetration = Double.POSITIVE_INFINITY
            var overlapNormal = Vec3.ZERO
            var overlapping = true
            for (axis in axes) {
                val radius = axis.radius + abs(axis.x) * halfX + abs(axis.y) * halfY + abs(axis.z) * halfZ
                val distance = rx * axis.x + ry * axis.y + rz * axis.z
                val velocity = displacement.x * axis.x + displacement.y * axis.y + displacement.z * axis.z
                val penetration = radius - abs(distance)
                if (penetration < -1e-9) overlapping = false
                if (penetration < minimumPenetration) {
                    minimumPenetration = penetration
                    val sign = if (distance < 0.0) -1.0 else 1.0
                    overlapNormal = Vec3(axis.x * sign, axis.y * sign, axis.z * sign)
                }
                if (abs(velocity) < 1e-12) {
                    if (penetration < -1e-9) return null
                    continue
                }
                val a = (-radius - distance) / velocity
                val b = (radius - distance) / velocity
                val first = min(a, b); val last = max(a, b)
                if (first > entry) {
                    entry = first
                    val sign = if (velocity > 0.0) -1.0 else 1.0
                    entryNormal = Vec3(axis.x * sign, axis.y * sign, axis.z * sign)
                }
                exit = min(exit, last)
                if (entry > exit + 1e-9) return null
            }
            if (exit < 0.0 || entry > 1.0) return null
            if (overlapping) {
                if (minimumPenetration <= 1e-8 && displacement.dot(overlapNormal) > 0.0) return null
                return Contact(0.0, overlapNormal, true, minimumPenetration.coerceAtLeast(0.0))
            }
            return Contact(entry.coerceIn(0.0, 1.0), entryNormal, false)
        }

        /** Obstacle face clipped to this box at a contact time, in world coordinates. */
        fun contactPatch(face: List<Vec3>, displacement: Vec3): List<Vec3> {
            var polygon = face
            val position = Vec3(center.x, center.y, center.z).add(displacement)
            for (axis in 0..2) for (sign in listOf(-1.0, 1.0)) {
                val direction = basis[axis]
                val normal = Vec3(direction.x * sign, direction.y * sign, direction.z * sign)
                val limit = position.dot(normal) + extents[axis]
                polygon = FixedWingContactSurface.clip(polygon, normal, limit)
                if (polygon.isEmpty()) return polygon
            }
            return polygon
        }
    }
}
