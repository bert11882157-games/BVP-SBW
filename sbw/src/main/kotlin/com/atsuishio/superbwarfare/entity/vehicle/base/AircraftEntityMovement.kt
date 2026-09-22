package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Native axis order with exact swept AABB/OBB clipping; an OBB's envelope is never a solid. */
internal object AircraftEntityMovement {
    fun resolve(requested: Vec3, bounds: AABB, parts: List<OBB>,
                nativeCollision: (Vec3, AABB) -> Vec3): Vec3 {
        var current = bounds
        val admitted = doubleArrayOf(requested.x, requested.y, requested.z)
        val order = if (abs(requested.x) < abs(requested.z)) intArrayOf(1, 2, 0) else intArrayOf(1, 0, 2)
        for (axis in order) {
            if (admitted[axis] == 0.0) continue
            val native = nativeCollision(vector(axis, admitted[axis]), current)
            var amount = component(native, axis)
            for (part in parts) {
                val movement = vector(axis, amount)
                // Relative motion lets the existing oriented sweep test a moving ordinary AABB.
                val contact = FixedWingContactSweep.Body(part).sweep(movement.reverse(), current) ?: continue
                val normal = contact.normal.reverse()
                if (movement.dot(normal) >= -1e-10) continue
                amount *= contact.fraction
            }
            admitted[axis] = amount
            current = current.move(vector(axis, amount))
        }
        return Vec3(admitted[0], admitted[1], admitted[2])
    }

    private fun vector(axis: Int, value: Double) = when (axis) {
        0 -> Vec3(value, 0.0, 0.0)
        1 -> Vec3(0.0, value, 0.0)
        else -> Vec3(0.0, 0.0, value)
    }
    private fun component(value: Vec3, axis: Int) = when (axis) {
        0 -> value.x
        1 -> value.y
        else -> value.z
    }
}
