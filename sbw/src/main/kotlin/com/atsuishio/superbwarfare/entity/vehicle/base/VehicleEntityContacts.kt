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
    data class Part(val box: OBB, val body: Boolean, val zone: VehicleImpactModel.Zone = VehicleImpactModel.Zone.BODY)
    data class Contact(val fraction: Double, val ownBody: Boolean, val otherBody: Boolean)

    /**
     * Contact for [VehicleImpactModel]: [normal] points from the other vehicle toward this one; [depth] > 0 when
     * the two already overlap (the deepest overlapping part pair), [fraction] is the time of first touch this tick.
     */
    data class Impact(
        val fraction: Double, val normal: Vec3, val depth: Double,
        val ownZones: Set<VehicleImpactModel.Zone>, val otherZones: Set<VehicleImpactModel.Zone>,
    )

    fun parts(entity: Entity): List<Part> {
        if (entity is VehicleEntity) {
            entity.getAircraftCollisionSnapshot(1F)?.let { snapshot ->
                return snapshot.parts.filter { it.active }.map {
                    Part(it.toObb(), it.role == AircraftCollisionRole.FUSELAGE, when {
                        it.role == AircraftCollisionRole.LANDING_GEAR -> VehicleImpactModel.Zone.GEAR
                        it.wingSide != 0 -> VehicleImpactModel.Zone.WING
                        else -> VehicleImpactModel.Zone.BODY
                    })
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

    /**
     * First contact between [own] moving by [relativeMovement] (own velocity minus other velocity) and [other] held
     * still, or the deepest current overlap. Null when they neither touch nor meet within the tick.
     */
    fun impact(own: List<Part>, other: List<Part>, relativeMovement: Vec3): Impact? {
        var overlapDepth = -1.0
        var overlapNormal = Vec3.ZERO
        val overlapOwn = HashSet<VehicleImpactModel.Zone>()
        val overlapOther = HashSet<VehicleImpactModel.Zone>()
        var fraction = Double.POSITIVE_INFINITY
        var sweepNormal = Vec3.ZERO
        val sweepOwn = HashSet<VehicleImpactModel.Zone>()
        val sweepOther = HashSet<VehicleImpactModel.Zone>()
        for (first in own) for (second in other) {
            val hit = sweep(first.box, second.box, relativeMovement) ?: continue
            if (hit.initiallyOverlapping) {
                overlapOwn += first.zone; overlapOther += second.zone
                if (hit.penetrationDepth > overlapDepth) {
                    overlapDepth = hit.penetrationDepth
                    overlapNormal = hit.normal
                }
            } else if (hit.fraction < fraction - 1e-8) {
                fraction = hit.fraction; sweepNormal = hit.normal
                sweepOwn.clear(); sweepOther.clear()
                sweepOwn += first.zone; sweepOther += second.zone
            } else if (abs(hit.fraction - fraction) <= 1e-8) {
                sweepOwn += first.zone; sweepOther += second.zone
            }
        }
        if (overlapDepth >= 0.0 && overlapNormal.lengthSqr() > 1e-12)
            return Impact(0.0, overlapNormal, overlapDepth.coerceAtLeast(1e-4), overlapOwn, overlapOther)
        if (fraction.isFinite() && sweepNormal.lengthSqr() > 1e-12)
            return Impact(fraction, sweepNormal, 0.0, sweepOwn, sweepOther)
        return null
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
