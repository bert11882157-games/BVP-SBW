package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
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

    fun plan(data: AircraftTerrainContact, frame: Matrix4d, roll: Double, weight: Double,
             captureGap: Double = 0.0, bonePose: ((String) -> Matrix4d)? = null): Plan {
        val samples = AircraftWheelGeometry.sample(data, frame)
        val support = contacts(samples)
        val unchanged = Plan(frame, 0.0, support)
        if (!support.complete || weight <= 0.0 || abs(roll) > 15.0) return unchanged
        if (support.rows.isEmpty() && captureGap > 0.0) {
            val floors = samples.map { it to floor(it, captureGap.coerceIn(0.0, 0.15)) }
            if (floors.any { !it.second.complete }) return unchanged
            val gap = floors.mapNotNull { (wheel, floor) -> floor.point?.let { wheel.world.y - it.y } }
                .filter { it in 0.0..captureGap.coerceAtMost(0.15) }.minOrNull() ?: return unchanged
            val movement = Vec3(0.0, -gap, 0.0)
            val bodies = AircraftCollisionSnapshot.create(data, frame, 0F, bonePose = bonePose).terrainInfos()
            val clearance = query(bodies, movement, Vec3.ZERO)
            if (!clearance.complete || clearance.bodyOverlap || clearance.contact?.let {
                    it.fraction < 1.0 - 1e-6 || it.penetrationDepth > 1e-6 } == true) return unchanged
            val lowered = Matrix4d().translation(0.0, -gap, 0.0).mul(frame)
            return plan(data, lowered, roll, weight, bonePose = bonePose)
        }
        val mains = support.rows.filter { it.wheel.group == AircraftWheelContactGroup.MAIN }
            .ifEmpty { support.rows }
        val secondaryGroup = when {
            samples.any { it.group == AircraftWheelContactGroup.NOSE } -> AircraftWheelContactGroup.NOSE
            samples.any { it.group == AircraftWheelContactGroup.TAIL } -> AircraftWheelContactGroup.TAIL
            else -> AircraftWheelContactGroup.MAIN
        }
        if (mains.isEmpty()) return unchanged
        // Once both longitudinal support groups touch, further pitch changes would lift one
        // of them again on authored multi-bogie layouts. Roll is handled by the ground solver.
        if (secondaryGroup != AircraftWheelContactGroup.MAIN &&
            support.rows.any { it.wheel.group == AircraftWheelContactGroup.MAIN } &&
            support.rows.any { it.wheel.group == secondaryGroup }) return unchanged
        // Tandem main bogies have no nose/tail wheel. Settle toward an unsupported main
        // contact while preserving every existing support point, using the same swept path.
        val supportedIds = support.rows.map { it.wheel.id }.toSet()
        // After a nose-first landing, settle the missing main group before another
        // wheel on the same nose bogie. Otherwise the nose contacts can alternate
        // forever while the main gear remains suspended.
        val targetGroup = if (support.rows.any { it.wheel.group == AircraftWheelContactGroup.MAIN })
            secondaryGroup else AircraftWheelContactGroup.MAIN
        val secondary = samples.filter { it.id !in supportedIds }
            .minWithOrNull(compareBy<AircraftWheelGeometry.Wheel> { if (it.group == targetGroup) 0 else 1 }
                .thenBy { it.world.y })
            ?: return unchanged
        val target = floor(secondary, (abs(secondary.world.y - mains.first().point.y) + 1.0).coerceAtMost(16.0))
        if (!target.complete || target.point == null) return unchanged
        // Multiple main bogies need the support edge which leaves every other supported tyre above ground.
        // Test that cheap exact constraint before any swept world queries.
        for (main in mains) {
            var anchor = main.wheel.localSupport
            var delta = AircraftWheelGeometry.settleDelta(frame, anchor, secondary.localSupport,
                target.point.y, roll, weight)
            if (abs(delta) <= 1e-7) continue
            if (main.wheel.definition.bounds != null) {
                // A level tyre supports on a face, not its center alone. Pivot about
                // the edge that becomes lowest, otherwise any tilt penetrates the floor
                // and leaves unequal-height source gear permanently suspended.
                val probe = AircraftWheelGeometry.pitchAround(frame, anchor, roll, delta)
                val nextAnchor = AircraftWheelGeometry.sample(data, probe).first { it.id == main.wheel.id }.localSupport
                val gap = AircraftWheelGeometry.transform(frame,nextAnchor).y - main.wheel.world.y
                if (gap > 1e-8 && nextAnchor.distanceToSqr(anchor) > 1e-12) {
                    // Stop exactly at the face transition. Jumping across it would put
                    // the opposite edge through the runway and lock a nose-first landing.
                    var low = 0.0; var high = 1.0
                    repeat(30) {
                        val fraction = (low + high) * .5
                        val middle = AircraftWheelGeometry.pitchAround(frame,anchor,roll,delta*fraction)
                        if (AircraftWheelGeometry.transform(middle,nextAnchor).y > main.wheel.world.y)
                            low = fraction else high = fraction
                    }
                    delta *= (low + high) * .5
                } else {
                    anchor = nextAnchor
                    delta = AircraftWheelGeometry.settleDelta(frame, anchor, secondary.localSupport,
                        target.point.y, roll, weight)
                }
                if (abs(delta) <= 1e-7) continue
            }
            val next = AircraftWheelGeometry.pitchAround(frame, anchor, roll, delta)
            val nextWheels = AircraftWheelGeometry.sample(data, next).associateBy { it.id }
            if (support.rows.any { nextWheels.getValue(it.wheel.id).world.y < it.point.y - 1e-6 }) continue
            if (!clearSettlingPath(data, frame, next, samples, anchor, roll, delta, bonePose)) continue
            val settledContacts = contacts(AircraftWheelGeometry.sample(data, next))
            if (!settledContacts.complete) return unchanged
            return Plan(next, delta, settledContacts)
        }
        return unchanged
    }

    private fun clearSettlingPath(data: AircraftTerrainContact, frame: Matrix4d, next: Matrix4d,
                                  samples: List<AircraftWheelGeometry.Wheel>, anchor: Vec3,
                                  roll: Double, delta: Double, bonePose: ((String) -> Matrix4d)?): Boolean {
        val midpoint = AircraftWheelGeometry.pitchAround(frame, anchor, roll, delta * 0.5)
        val bodies = AircraftCollisionSnapshot.create(data, midpoint, 0F, bonePose = bonePose).terrainInfos().filterNot { it.landingGear }
        for ((body, volume) in bodies.zip(data.bodyVolumes())) {
            var localCenter = volume.minimum.add(volume.maximum).scale(0.5)
            if (volume.bone != "hull") {
                val posed = requireNotNull(bonePose)(volume.bone)
                    .transformPosition(Vector3d(localCenter.x, localCenter.y, localCenter.z))
                localCenter = Vec3(posed.x, posed.y, posed.z)
            }
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
