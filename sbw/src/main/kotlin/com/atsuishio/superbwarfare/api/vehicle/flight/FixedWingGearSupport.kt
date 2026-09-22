package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

/** Vertical wheel support at the admitted horizontal destination; all distances are blocks. */
internal object FixedWingGearSupport {
    const val CONTACT_EPSILON = 1e-6

    fun rotationCreatedFloorOverlap(
        body: FixedWingContactSweep.Body, previous: FixedWingContactSweep.Body?,
        contact: FixedWingContactSweep.Contact, movement: Vec3, obstacle: AABB, supportPlane: Double?,
    ): Boolean {
        if (previous == null || supportPlane == null || !contact.initiallyOverlapping || movement.y <= 0.0 ||
            obstacle.maxY > supportPlane + 0.00501 || body.bounds.minY + movement.y < obstacle.maxY - 1e-7) {
            return false
        }
        // Ground support does not prove a clear fuselage; retain any pre-existing body scrape.
        return (previous.sweep(Vec3.ZERO, obstacle)?.penetrationDepth ?: 0.0) <= 1e-7
    }

    fun supportHeight(gear: List<OBB>, terrain: List<AABB>, movement: Vec3, rotationLift: Double): Double? {
        require(rotationLift.isFinite() && rotationLift in 0.0..0.5)
        if (movement.y > rotationLift + CONTACT_EPSILON) return null
        val start = Vec3(movement.x, rotationLift + CONTACT_EPSILON, movement.z)
        val fall = Vec3(0.0, min(movement.y, 0.0) - start.y - CONTACT_EPSILON, 0.0)
        var support: Double? = null
        for (wheel in gear) {
            val body = FixedWingContactSweep.Body(wheel.move(start))
            for (obstacle in terrain) {
                if (!body.bounds.expandTowards(fall).intersects(obstacle)) continue
                val contact = body.sweep(fall, obstacle) ?: continue
                // An embedded wheel or a wall beside it cannot certify a floor underneath it.
                if (contact.penetrationDepth > CONTACT_EPSILON) continue
                val y = obstacle.maxY
                val face = listOf(Vec3(obstacle.minX, y, obstacle.minZ),
                    Vec3(obstacle.maxX, y, obstacle.minZ), Vec3(obstacle.maxX, y, obstacle.maxZ),
                    Vec3(obstacle.minX, y, obstacle.maxZ))
                if (body.contactPatch(face, fall.scale(contact.fraction)).isEmpty()) continue
                val height = start.y + fall.y * contact.fraction
                if (height < movement.y - CONTACT_EPSILON || height > rotationLift + CONTACT_EPSILON) continue
                support = max(support ?: height, height)
            }
        }
        return support
    }
}
