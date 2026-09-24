package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d

/** Counter-swiveling pylons remain longitudinal while their suspension point follows the wing. */
data class AircraftMountSweep(val pivot: Vec3, val axis: Vec3, val maxDegrees: Double,
                              val points: List<Pair<Double, Double>>) {
    fun position(point: Vec3, speed: Double): Vec3 {
        val fraction = fraction(speed)
        if (fraction == 0.0) return point
        val offset = point.subtract(pivot)
        val rotated = Quaterniond().rotationAxis(Math.toRadians(maxDegrees * fraction),
            axis.x, axis.y, axis.z).transform(Vector3d(offset.x, offset.y, offset.z))
        return pivot.add(rotated.x, rotated.y, rotated.z)
    }

    fun fraction(speed: Double): Double {
        if (!speed.isFinite() || speed <= 0.0) return 0.0
        for (i in 1 until points.size) if (speed < points[i].first) {
            val a = points[i - 1]; val b = points[i]
            return a.second + (b.second - a.second) * (speed - a.first) / (b.first - a.first)
        }
        return 1.0
    }

    companion object {
        fun decode(mount: JsonObject, positions: Int): List<AircraftMountSweep> {
            val frames = mount.getAsJsonArray("SweepFrames") ?: return emptyList()
            require(mount["Internal"]?.asBoolean != true && frames.size() == positions)
            return frames.map { entry ->
                val frame = entry.asJsonObject
                val pivot = requireNotNull(AircraftArmamentRegistry.vector(frame["Pivot"]))
                require(pivot.length() <= 128.0)
                val axis = requireNotNull(AircraftArmamentRegistry.vector(frame["Axis"]))
                require(axis.lengthSqr() in 0.999..1.001)
                val angle = frame["MaxDegrees"].asDouble
                require(angle.isFinite() && kotlin.math.abs(angle) in 0.001..90.0)
                val raw = frame.getAsJsonArray("Points")
                require(raw.size() in 2..16)
                val points = raw.map { value ->
                    val point = value.asJsonArray
                    require(point.size() == 2)
                    val speed = point[0].asDouble; val fraction = point[1].asDouble
                    require(speed.isFinite() && speed in 0.0..100000.0 && fraction.isFinite() && fraction in 0.0..1.0)
                    speed to fraction
                }
                require(points.first() == (0.0 to 0.0) && points.last().second == 1.0)
                require(points.zipWithNext().all { (a,b) -> b.first > a.first && b.second >= a.second })
                AircraftMountSweep(pivot, axis.normalize(), angle, points)
            }
        }

        /** Server shot point; rack spacing is downstream of the swiveling pylon, so stays level. */
        fun position(mount: JsonObject, index: Int, point: Vec3, speed: Double): Vec3 {
            val positions = AircraftArmamentRegistry.mountPositions(mount)
            val sweep = decode(mount, positions.size).getOrNull(index) ?: return point
            return point.add(sweep.position(positions[index], speed).subtract(positions[index]))
        }
    }
}
