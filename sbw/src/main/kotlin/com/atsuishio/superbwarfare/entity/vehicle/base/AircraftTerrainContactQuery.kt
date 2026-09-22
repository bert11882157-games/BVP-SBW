package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSurface
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Shared exposed-surface admission for the authored aircraft terrain volumes. */
internal object AircraftTerrainContactQuery {
    private const val GEAR_FLOOR_TOLERANCE_BLOCKS = 1e-4

    fun resolve(body: FixedWingContactSweep.Body, box: OBB, gear: Boolean, movement: Vec3,
                obstacle: AABB, raw: FixedWingContactSweep.Contact, terrain: List<AABB>,
                budget: FixedWingContactSurface.Budget): AircraftTerrainMotionSolver.Query {
        if (gear && box.extents.lengthSquared() == 0.0) {
            // Degenerate points need exact face incidence: polygon clipping can lose a tyre
            // crossing a ledge side at a small nonzero pitch through roundoff.
            val point = body.bounds.center.add(movement.scale(raw.fraction))
            val coordinates = doubleArrayOf(point.x, point.y, point.z)
            val minimum = doubleArrayOf(obstacle.minX, obstacle.minY, obstacle.minZ)
            val maximum = doubleArrayOf(obstacle.maxX, obstacle.maxY, obstacle.maxZ)
            var normal: Vec3? = null
            var speed = 0.0
            for (axis in 0..2) for (positive in listOf(false, true)) {
                val plane = if (positive) maximum[axis] else minimum[axis]
                if (abs(coordinates[axis] - plane) > 1e-6) continue
                val sign = if (positive) 1.0 else -1.0
                val candidate = Vec3(if (axis == 0) sign else 0.0, if (axis == 1) sign else 0.0,
                    if (axis == 2) sign else 0.0)
                val closing = -movement.dot(candidate)
                if (closing <= 1e-9 || !AircraftTerrainMotionSolver.acceptsGearContact(candidate, box)) continue
                var exposed = true
                for (neighbor in terrain) {
                    if (neighbor === obstacle) continue
                    if (--budget.remaining < 0) return AircraftTerrainMotionSolver.Query(null, true, false)
                    val low = doubleArrayOf(neighbor.minX, neighbor.minY, neighbor.minZ)
                    val high = doubleArrayOf(neighbor.maxX, neighbor.maxY, neighbor.maxZ)
                    val crosses = if (positive) low[axis] <= plane + 1e-7 && high[axis] > plane + 1e-7
                        else high[axis] >= plane - 1e-7 && low[axis] < plane - 1e-7
                    // At a floor/ledge edge the floor ends at the tyre's Y: it cannot hide
                    // the ledge side immediately above that edge. Require transverse interior.
                    if (crosses && (0..2).all { it == axis || coordinates[it] > low[it] + 1e-7 &&
                            coordinates[it] < high[it] - 1e-7 }) { exposed = false; break }
                }
                if (exposed && closing > speed) { normal = candidate; speed = closing }
            }
            if (normal != null) return AircraftTerrainMotionSolver.Query(raw.copy(normal = normal), true, true)
            if (!raw.initiallyOverlapping) return AircraftTerrainMotionSolver.Query(null, true, true)
        }
        if (gear && box.extents.lengthSquared() == 0.0 && raw.initiallyOverlapping) {
            // Rotation can put an exact tyre point just below its supporting top face.
            // A point has no face area to clip; certify that specific column instead.
            val point = body.bounds.center
            val depth = obstacle.maxY - point.y
            var exposed = depth in 0.0..0.5 && movement.y <= 0.0 &&
                (depth > 1e-7 || movement.y < -1e-9) &&
                AircraftTerrainMotionSolver.acceptsGearContact(Vec3(0.0, 1.0, 0.0), box)
            if (exposed) for (neighbor in terrain) {
                if (neighbor === obstacle) continue
                if (--budget.remaining < 0) return AircraftTerrainMotionSolver.Query(null, true, false)
                if (point.x >= neighbor.minX && point.x <= neighbor.maxX &&
                    point.z >= neighbor.minZ && point.z <= neighbor.maxZ &&
                    neighbor.minY <= obstacle.maxY + 1e-7 && neighbor.maxY > obstacle.maxY + 1e-7) {
                    exposed = false
                    break
                }
            }
            return AircraftTerrainMotionSolver.Query(if (exposed) raw.copy(
                normal = Vec3(0.0, 1.0, 0.0), penetrationDepth = depth) else null, true, true)
        }
        val shallowGearFloor = gear && raw.initiallyOverlapping &&
            obstacle.maxY - body.bounds.minY in -1e-7..GEAR_FLOOR_TOLERANCE_BLOCKS &&
            AircraftTerrainMotionSolver.acceptsGearContact(Vec3(0.0, 1.0, 0.0), box)
        val surface = FixedWingContactSurface.resolve(body, movement, obstacle, raw, terrain,
            budget, allowGearSupportPoint = gear, preferUpwardSupport = shallowGearFloor)
        val normal = surface.normal ?: return AircraftTerrainMotionSolver.Query(null, gear, surface.complete)
        val leavingShallowFloor = shallowGearFloor && normal.y > 0.5 && movement.dot(normal) >= -1e-9
        val admitted = (!gear || AircraftTerrainMotionSolver.acceptsGearContact(normal, box)) &&
            !leavingShallowFloor &&
            (raw.penetrationDepth > 1e-7 || movement.dot(normal) < -1e-9)
        return AircraftTerrainMotionSolver.Query(if (admitted) raw.copy(normal = normal) else null,
            gear, surface.complete)
    }

    fun preferred(contact: FixedWingContactSweep.Contact, gear: Boolean,
                  previous: FixedWingContactSweep.Contact?, previousGear: Boolean): Boolean =
        previous == null || contact.fraction < previous.fraction - 1e-8 ||
            (abs(contact.fraction - previous.fraction) <= 1e-8 && !gear && previousGear)
}
