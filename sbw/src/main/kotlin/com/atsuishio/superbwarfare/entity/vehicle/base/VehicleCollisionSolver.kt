package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Stateless stepping solver. Ground authority is an input, never a global/entity mutation. */
internal object VehicleCollisionSolver {
    fun collide(
        requested: Vec3,
        bounds: AABB,
        groundedForStepping: Boolean,
        stepHeight: Double,
        resolve: (Vec3, AABB) -> Vec3,
    ): Vec3 {
        if (requested.lengthSqr() == 0.0) return requested
        val resolved = resolve(requested, bounds)
        val collidedX = requested.x != resolved.x
        val collidedY = requested.y != resolved.y
        val collidedZ = requested.z != resolved.z
        val canStep = groundedForStepping || collidedY && requested.y < 0.0
        if (stepHeight <= 0.0 || !canStep || (!collidedX && !collidedZ)) return resolved

        var step = resolve(Vec3(requested.x, stepHeight, requested.z), bounds)
        val vertical = resolve(Vec3(0.0, stepHeight, 0.0), bounds.expandTowards(requested.x, 0.0, requested.z))
        if (vertical.y < stepHeight) {
            val horizontal = resolve(Vec3(requested.x, 0.0, requested.z), bounds.move(vertical)).add(vertical)
            if (horizontal.horizontalDistanceSqr() > step.horizontalDistanceSqr()) step = horizontal
        }
        if (step.horizontalDistanceSqr() <= resolved.horizontalDistanceSqr()) return resolved
        return step.add(resolve(Vec3(0.0, -step.y + requested.y, 0.0), bounds.move(step)))
    }
}
