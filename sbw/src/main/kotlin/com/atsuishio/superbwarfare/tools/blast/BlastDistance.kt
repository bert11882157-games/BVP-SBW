package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.sqrt

/**
 * Distance from a blast centre to a vehicle (owner 2026-09-29, "munitions underperforming"): the nearest point of its
 * hull boxes, not of its entity box. Fitted vehicles register a core box of at most 3 x 3 m and the original ones a
 * square far shorter than the hull, so a bomb against a tank's nose used to count as 2 m or more away.
 */
object BlastDistance {
    /** How far hull boxes may stick out of a vehicle's entity box (blocks): widens the blast entity query. */
    const val VEHICLE_QUERY_MARGIN = 4.0

    @JvmStatic
    fun toVehicle(vehicle: VehicleEntity, point: Vec3): Double {
        var best = sqrt(vehicle.boundingBox.distanceToSqr(point))
        if (best <= 0.0) return 0.0
        val boxes = runCatching { vehicle.getOBBs() }.getOrNull() ?: return best
        for (box in boxes) {
            val d = toBox(box.center, box.extents, box.rotation, point.x, point.y, point.z)
            if (d < best) best = d
            if (best <= 0.0) return 0.0
        }
        return best
    }

    @JvmStatic
    fun toBox(box: OBB, point: Vec3): Double = toBox(box.center, box.extents, box.rotation, point.x, point.y, point.z)

    /** Distance from a point to an oriented box ([extents] are half sizes, [rotation] box to world); 0 inside. */
    @JvmStatic
    fun toBox(center: Vector3d, extents: Vector3d, rotation: Quaterniond, x: Double, y: Double, z: Double): Double {
        val local = Vector3d(x - center.x, y - center.y, z - center.z)
        Quaterniond(rotation).invert().transform(local)
        val dx = excess(local.x, extents.x)
        val dy = excess(local.y, extents.y)
        val dz = excess(local.z, extents.z)
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        return if (d.isFinite()) d else Double.POSITIVE_INFINITY
    }

    private fun excess(v: Double, half: Double): Double {
        val a = kotlin.math.abs(v) - kotlin.math.abs(half)
        return if (a > 0.0) a else 0.0
    }
}
