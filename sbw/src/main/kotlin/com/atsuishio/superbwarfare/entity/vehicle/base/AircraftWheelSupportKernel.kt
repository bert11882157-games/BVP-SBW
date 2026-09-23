package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import kotlin.math.abs
import kotlin.math.sin

/** The production support transaction, with terrain access supplied by the bounded world probe. */
internal class AircraftWheelSupportKernel(
    private val query: (List<OBBInfo>, Vec3, Vec3) -> Probe,
) {
    data class Probe(val contact: FixedWingContactSweep.Contact?, val complete: Boolean,
                     val bodyOverlap: Boolean = false)
    data class Contact(val wheel: AircraftWheelGeometry.Wheel, val point: Vec3)
    data class Contacts(val rows: List<Contact>, val complete: Boolean)
    data class Plan(val frame: Matrix4d, val deltaPitch: Double, val contacts: Contacts)
    private data class Floor(val point: Vec3?, val complete: Boolean)

    private fun floor(wheel: AircraftWheelGeometry.Wheel, reach: Double = CONTACT_EPSILON): Floor {
        val down = Vec3(0.0, -reach - CONTACT_EPSILON, 0.0)
        val sample = query(listOf(wheel.terrainInfo()), down, Vec3(0.0, CONTACT_EPSILON, 0.0))
        val hit = sample.contact
        return Floor(if (sample.complete && hit != null && hit.normal.y > 0.5 &&
            hit.penetrationDepth <= 1e-6) wheel.world.add(0.0,
                CONTACT_EPSILON + down.y * hit.fraction, 0.0) else null, sample.complete)
    }

    fun contacts(samples: List<AircraftWheelGeometry.Wheel>): Contacts {
        var complete = true
        val rows = samples.mapNotNull { wheel ->
            val result = floor(wheel)
            complete = complete && result.complete
            result.point?.let { Contact(wheel, it) }
        }
        return Contacts(rows, complete)
    }

    fun plan(data: AircraftTerrainContact, frame: Matrix4d, roll: Double, weight: Double): Plan {
        val samples = AircraftWheelGeometry.sample(data, frame)
        val support = contacts(samples)
        val unchanged = Plan(frame, 0.0, support)
        if (!support.complete || weight <= 0.0 || abs(roll) > 15.0) return unchanged
        val mains = support.rows.filter { it.wheel.group == AircraftWheelContactGroup.MAIN }
        val secondaryGroup = when {
            samples.any { it.group == AircraftWheelContactGroup.NOSE } -> AircraftWheelContactGroup.NOSE
            samples.any { it.group == AircraftWheelContactGroup.TAIL } -> AircraftWheelContactGroup.TAIL
            else -> AircraftWheelContactGroup.MAIN
        }
        if (mains.isEmpty()) return unchanged
        // Tandem main bogies have no nose/tail wheel. Settle toward an unsupported main
        // contact while preserving every existing support point, using the same swept path.
        val tandem = secondaryGroup == AircraftWheelContactGroup.MAIN
        if (!tandem && support.rows.any { it.wheel.group == secondaryGroup }) return unchanged
        val supportedIds = support.rows.map { it.wheel.id }.toSet()
        val secondary = samples.filter { it.group == secondaryGroup && (!tandem || it.id !in supportedIds) }
            .minByOrNull { it.world.y }
            ?: return unchanged
        val target = floor(secondary, (abs(secondary.world.y - mains.first().point.y) + 1.0).coerceAtMost(16.0))
        if (!target.complete || target.point == null) return unchanged
        // Multiple main bogies need the support edge which leaves every other supported tyre above ground.
        // Test that cheap exact constraint before any swept world queries.
        for (main in mains) {
            val anchor = main.wheel.localSupport
            val delta = AircraftWheelGeometry.settleDelta(frame, anchor, secondary.localSupport,
                target.point.y, roll, weight)
            if (abs(delta) <= 1e-7) continue
            val next = AircraftWheelGeometry.pitchAround(frame, anchor, roll, delta)
            val nextWheels = AircraftWheelGeometry.sample(data, next).associateBy { it.id }
            if (support.rows.any { nextWheels.getValue(it.wheel.id).world.y < it.point.y - 1e-6 }) continue
            if (!clearSettlingPath(data, frame, next, samples, anchor, roll, delta)) continue
            val settledContacts = contacts(AircraftWheelGeometry.sample(data, next))
            if (!settledContacts.complete) return unchanged
            return Plan(next, delta, settledContacts)
        }
        return unchanged
    }

    private fun clearSettlingPath(data: AircraftTerrainContact, frame: Matrix4d, next: Matrix4d,
                                  samples: List<AircraftWheelGeometry.Wheel>, anchor: Vec3,
                                  roll: Double, delta: Double): Boolean {
        val midpoint = AircraftWheelGeometry.pitchAround(frame, anchor, roll, delta * 0.5)
        val bodies = AircraftCollisionSnapshot.create(data, midpoint, 0F).terrainInfos().filterNot { it.landingGear }
        for ((body, volume) in bodies.zip(data.bodyVolumes())) {
            val localCenter = volume.minimum.add(volume.maximum).scale(0.5)
            val radius = localCenter.distanceTo(anchor) + volume.maximum.subtract(volume.minimum).length() * 0.5
            val margin = 2.0 * radius * sin(Math.toRadians(abs(delta)) * 0.25)
            // Includes each body's complete rotating arc, without filling the spaces between wings.
            body.getOBB().extents.add(margin, margin, margin)
        }
        val clearance = query(bodies, Vec3.ZERO, Vec3.ZERO)
        if (!clearance.complete || clearance.bodyOverlap || clearance.contact != null) return false
        val end = AircraftWheelGeometry.sample(data, next).associateBy { it.id }
        if (data.hasWheelVolumes()) {
            // Rotating about a tyre contact also moves its box center. Sweeping the old box
            // with that center translation falsely drives its frozen lower face into the floor.
            // Validate the intermediate/end solids, then sweep the actual support points.
            for (pose in listOf(midpoint, next)) {
                val gearClearance = query(AircraftWheelGeometry.sample(data, pose).map { it.terrainInfo() },
                    Vec3.ZERO, Vec3.ZERO)
                if (!gearClearance.complete || gearClearance.bodyOverlap ||
                    gearClearance.contact?.let { it.penetrationDepth > 1e-6 } == true) return false
            }
        }
        for (wheel in samples) {
            val movement = end.getValue(wheel.id).world.subtract(wheel.world)
            if (movement.lengthSqr() <= 1e-14) continue
            val hit = query(listOf(AircraftWheelGeometry.pointInfo(wheel.world, wheel.orientation)), movement, Vec3.ZERO)
            if (!hit.complete || hit.contact?.let { it.fraction < 1.0 - 1e-6 || it.penetrationDepth > 1e-6 } == true)
                return false
        }
        return true
    }

    private companion object { const val CONTACT_EPSILON = 0.003 }
}
