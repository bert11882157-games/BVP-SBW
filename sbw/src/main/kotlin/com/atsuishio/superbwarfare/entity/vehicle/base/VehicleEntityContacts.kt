package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.entity.OBBEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs

/** Physical contact roles for vehicle support/strikes; projectile OBB selection is separate. */
internal object VehicleEntityContacts {
    data class Part(val box: OBB, val body: Boolean)
    data class Contact(val fraction: Double, val ownBody: Boolean, val otherBody: Boolean)

    fun parts(entity: Entity): List<Part> {
        if (entity is VehicleEntity) {
            entity.getAircraftCollisionSnapshot(1F)?.let { snapshot ->
                return snapshot.parts.filter { it.active }.map {
                    Part(it.toObb(), it.role == AircraftCollisionRole.FUSELAGE)
                }
            }
        }
        if (entity is OBBEntity && !entity.enableAABB()) return entity.getOBBs().map { Part(it, true) }
        val bounds = entity.boundingBox
        return listOf(Part(OBB(OBB.vec3ToVector3d(bounds.center),
            Vector3d(bounds.xsize, bounds.ysize, bounds.zsize).mul(0.5), Quaterniond(), OBB.Part.BODY), true))
    }

    fun find(vehicle: VehicleEntity, other: Entity, movement: Vec3): Contact? =
        find(parts(vehicle), parts(other), movement)

    fun find(own: List<Part>, other: List<Part>, movement: Vec3): Contact? {
        var nearest: Contact? = null
        for (first in own) for (second in other) {
            val hit = sweep(first.box, second.box, movement) ?: continue
            val previous = nearest
            if (previous == null || hit.fraction < previous.fraction - 1e-8) {
                nearest = Contact(hit.fraction, first.body, second.body)
            } else if (abs(hit.fraction - previous.fraction) <= 1e-8) {
                nearest = previous.copy(ownBody = previous.ownBody || first.body,
                    otherBody = previous.otherBody || second.body)
            }
        }
        return nearest
    }

    /** Expressing both boxes in the target frame preserves the existing exact OBB/AABB sweep. */
    fun sweep(first: OBB, second: OBB, movement: Vec3): FixedWingContactSweep.Contact? {
        val inverse = Quaterniond(second.rotation).conjugate()
        val center = inverse.transform(Vector3d(first.center).sub(second.center))
        val rotation = Quaterniond(inverse).mul(first.rotation)
        val relative = OBB(center, Vector3d(first.extents), rotation, OBB.Part.BODY)
        val localMotion = inverse.transform(Vector3d(movement.x, movement.y, movement.z))
        val extents = second.extents
        val hit = FixedWingContactSweep.Body(relative).sweep(Vec3(localMotion.x, localMotion.y, localMotion.z),
            AABB(-extents.x, -extents.y, -extents.z, extents.x, extents.y, extents.z)) ?: return null
        val normal = second.rotation.transform(Vector3d(hit.normal.x, hit.normal.y, hit.normal.z))
        return hit.copy(normal = Vec3(normal.x, normal.y, normal.z))
    }
}
