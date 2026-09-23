package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftWheelContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.max

/** Per-wheel/bogie solids and their current lowest support point; legacy points remain supported. */
internal object AircraftWheelGeometry {
    data class Wheel(val definition: AircraftWheelContact, val world: Vec3, val orientation: Quaterniond,
                     val localSupport: Vec3 = definition.position, val center: Vec3 = world) {
        val id get() = definition.id
        val group get() = definition.group
        fun terrainInfo() = pointInfo(center, orientation).apply {
            definition.bounds?.let {
                size = it.maximum.subtract(it.minimum).scale(0.5)
                getOBB().extents.set(size.x, size.y, size.z)
            }
        }
    }

    fun pointInfo(point: Vec3, orientation: Quaterniond = Quaterniond()) = OBBInfo().apply {
        size = Vec3.ZERO
        landingGear = true
        getOBB().center.set(point.x, point.y, point.z)
        getOBB().updateRotation(orientation)
    }

    fun sample(definition: AircraftTerrainContact, frame: Matrix4d): List<Wheel> {
        val orientation = frame.getNormalizedRotation(Quaterniond())
        return definition.wheelContacts.map { wheel ->
            val bounds = wheel.bounds
            if (bounds == null) Wheel(wheel, transform(frame, wheel.position), orientation)
            else {
                val corners = (0..7).map { bits -> Vec3(
                    if (bits and 1 == 0) bounds.minimum.x else bounds.maximum.x,
                    if (bits and 2 == 0) bounds.minimum.y else bounds.maximum.y,
                    if (bits and 4 == 0) bounds.minimum.z else bounds.maximum.z) }
                val lowest = corners.minOf { transform(frame, it).y }
                val supports = corners.filter { transform(frame, it).y <= lowest + 1e-8 }
                val local = supports.reduce(Vec3::add).scale(1.0 / supports.size)
                Wheel(wheel, transform(frame, local), orientation, local,
                    transform(frame, bounds.minimum.add(bounds.maximum).scale(0.5)))
            }
        }
    }

    /** Used only for step lookahead; no solid support spans the gaps between tyres. */
    fun envelope(wheels: List<Wheel>): OBB {
        val boxes = wheels.map { com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep.Body(it.terrainInfo().getOBB()).bounds }
        val min = Vec3(boxes.minOf { it.minX }, boxes.minOf { it.minY }, boxes.minOf { it.minZ })
        val max = Vec3(boxes.maxOf { it.maxX }, boxes.maxOf { it.maxY }, boxes.maxOf { it.maxZ })
        val center = min.add(max).scale(0.5)
        val half = max.subtract(min).scale(0.5)
        return OBB(Vector3d(center.x, center.y, center.z), Vector3d(half.x, half.y, half.z), Quaterniond(), OBB.Part.BODY)
    }

    fun transform(frame: Matrix4d, point: Vec3): Vec3 {
        val value = frame.transformPosition(Vector3d(point.x, point.y, point.z))
        return Vec3(value.x, value.y, value.z)
    }

    fun addRotation(expected: Map<String, Vec3>, before: List<Wheel>, after: List<Wheel>): Map<String, Vec3> {
        val old = before.associateBy { it.id }
        val next = after.associateBy { it.id }
        return expected.mapValues { (id, position) ->
            position.add(next.getValue(id).world.subtract(old.getValue(id).world))
        }
    }

    /** Closing velocity at each contact includes both body translation and the accepted rotation. */
    fun closingSpeeds(contacts: List<AircraftWheelSupportKernel.Contact>, previous: Map<String, Vec3>,
                      initial: Map<String, Vec3>, expected: Map<String, Vec3>): Map<AircraftWheelContactGroup, Double> =
        contacts.groupBy { it.wheel.group }.mapValues { (_, rows) -> rows.maxOf { row ->
            max(0.0, (previous[row.wheel.id] ?: initial.getValue(row.wheel.id)).y - expected.getValue(row.wheel.id).y)
        } }

    /** Changes native pitch about an exact supported main tyre, preserving its world point. */
    fun pitchAround(frame: Matrix4d, localAnchor: Vec3, rollDegrees: Double, deltaDegrees: Double): Matrix4d =
        Matrix4d(frame).translate(localAnchor.x, localAnchor.y, localAnchor.z)
            .rotateZ(Math.toRadians(-rollDegrees)).rotateX(Math.toRadians(deltaDegrees))
            .rotateZ(Math.toRadians(rollDegrees)).translate(-localAnchor.x, -localAnchor.y, -localAnchor.z)

    fun originCorrection(frame: Matrix4d, pitchedFrame: Matrix4d, nativePivot: Double,
                         rollDegrees: Double, deltaDegrees: Double): Vec3 {
        val natural = pitchAround(frame, Vec3(0.0, nativePivot, 0.0), rollDegrees, deltaDegrees)
        return transform(pitchedFrame, Vec3.ZERO).subtract(transform(natural, Vec3.ZERO))
    }

    /** Positive means the secondary wheel needs to descend; actual geometry selects pitch sign. */
    fun settleDelta(frame: Matrix4d, anchor: Vec3, secondary: Vec3, floorY: Double,
                    rollDegrees: Double, weight: Double): Double {
        val current = transform(frame, secondary)
        val gap = current.y - floorY
        if (gap <= 1e-5 || weight <= 0.0) return 0.0
        val tilted = transform(pitchAround(frame, anchor, rollDegrees, 0.01), secondary)
        val derivative = (tilted.y - current.y) / 0.01
        if (abs(derivative) < 1e-6) return 0.0
        // Bounded 8 degrees/second; proportional closure eases onto the tyre datum.
        return (-gap / derivative * 0.15).coerceIn(-0.4, 0.4) * weight.coerceIn(0.0, 1.0)
    }
}
