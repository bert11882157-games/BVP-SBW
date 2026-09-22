package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Rejects SAT contacts whose entire contact feature lies inside the terrain union. */
internal object FixedWingContactSurface {
    private const val EPSILON = 1e-7
    private const val MAX_FRAGMENTS = 128

    class Budget(var remaining: Int = 32768)
    data class Result(val normal: Vec3?, val complete: Boolean)

    /** An incomplete query retains proved faces; a complete null normal means internal contact. */
    fun resolve(body: FixedWingContactSweep.Body, movement: Vec3,
                obstacle: AABB, contact: FixedWingContactSweep.Contact,
                terrain: List<AABB>, budget: Budget, allowGearSupportPoint: Boolean = false,
                preferUpwardSupport: Boolean = false): Result {
        var best: Vec3? = null
        var bestSpeed = Double.NEGATIVE_INFINITY
        for (axis in 0..2) {
            val component = coordinate(contact.normal, axis)
            if (!contact.initiallyOverlapping && abs(component) < EPSILON) continue
            val sides = if (contact.initiallyOverlapping) listOf(false, true) else listOf(component > 0.0)
            for (positive in sides) {
                val plane = if (positive) upper(obstacle, axis) else lower(obstacle, axis)
                val u = (axis + 1) % 3
                val v = (axis + 2) % 3
                val face = listOf(
                    point(axis, plane, u, lower(obstacle, u), v, lower(obstacle, v)),
                    point(axis, plane, u, upper(obstacle, u), v, lower(obstacle, v)),
                    point(axis, plane, u, upper(obstacle, u), v, upper(obstacle, v)),
                    point(axis, plane, u, lower(obstacle, u), v, upper(obstacle, v)))
                val patch = body.contactPatch(face, movement.scale(contact.fraction))
                // A tilted landing volume rests on a point/edge. Rejecting that zero-area
                // upward feature makes it fall into the floor and depenetrate every few ticks.
                // Keep internal-face occlusion and all wall/body contact rules unchanged.
                val supportPoint = allowGearSupportPoint && axis == 1 && positive &&
                    contact.normal.y > 0.5 && contact.penetrationDepth <= 1e-6 && movement.y < 0.0
                if (patch.isEmpty() || (contact.initiallyOverlapping && !hasArea(patch) && !supportPoint)) continue
                var fragments = listOf(patch)
                for (neighbor in terrain) {
                    if (neighbor === obstacle) continue
                    if (--budget.remaining < 0) return Result(best, false)
                    // A touching neighbor covers this face only if solid extends outward from it.
                    val crosses = if (positive) lower(neighbor, axis) <= plane + EPSILON &&
                        upper(neighbor, axis) > plane + EPSILON else
                        upper(neighbor, axis) >= plane - EPSILON && lower(neighbor, axis) < plane - EPSILON
                    if (!crosses) continue
                    if (upper(neighbor, u) < lower(obstacle, u) - EPSILON ||
                        lower(neighbor, u) > upper(obstacle, u) + EPSILON ||
                        upper(neighbor, v) < lower(obstacle, v) - EPSILON ||
                        lower(neighbor, v) > upper(obstacle, v) + EPSILON) continue
                    val next = ArrayList<List<Vec3>>()
                    for (fragment in fragments) {
                        if (--budget.remaining < 0) return Result(best, false)
                        next.addAll(subtract(fragment, neighbor, u, v))
                        if (next.size > MAX_FRAGMENTS) return Result(best, false)
                    }
                    fragments = next
                    if (fragments.isEmpty()) break
                }
                if (fragments.any { !contact.initiallyOverlapping || hasArea(it) || supportPoint }) {
                    val normal = unit(axis, if (positive) 1.0 else -1.0)
                    // A shallowly embedded gear corner can expose a tiny ledge-side sliver.
                    // The certified top face supports it; forward speed must not select that
                    // sliver as a wall. Neighbor occlusion above still applies to this face.
                    if (preferUpwardSupport && axis == 1 && positive) return Result(normal, true)
                    val speed = -movement.dot(normal)
                    if (speed > bestSpeed) { best = normal; bestSpeed = speed }
                }
            }
        }
        // An embedded box proves overlap, not an impact direction. Only an exposed terrain
        // feature may contribute normal impact energy; the caller retains overlap separately.
        return Result(best, true)
    }

    private fun hasArea(polygon: List<Vec3>): Boolean {
        if (polygon.size < 3) return false
        val origin = polygon.first()
        var area = Vec3.ZERO
        for (index in 1 until polygon.size - 1) {
            area = area.add(polygon[index].subtract(origin).cross(polygon[index + 1].subtract(origin)))
        }
        return area.lengthSqr() > 1e-18
    }

    private fun subtract(polygon: List<Vec3>, cover: AABB, u: Int, v: Int): List<List<Vec3>> {
        var inside = polygon
        val outside = ArrayList<List<Vec3>>()
        for (axis in listOf(u, v)) for (positive in listOf(false, true)) {
            val normal = unit(axis, if (positive) 1.0 else -1.0)
            val limit = if (positive) upper(cover, axis) else -lower(cover, axis)
            // Coverage includes the shared edge; a zero-area edge must not become a fake wall.
            val remainder = clip(inside, normal.scale(-1.0), -limit - EPSILON * 2)
            if (remainder.isNotEmpty()) outside.add(remainder)
            inside = clip(inside, normal, limit + EPSILON)
            if (inside.isEmpty()) break
        }
        return outside
    }

    internal fun clip(polygon: List<Vec3>, normal: Vec3, limit: Double): List<Vec3> {
        if (polygon.isEmpty()) return emptyList()
        val result = ArrayList<Vec3>()
        var previous = polygon.last()
        var previousDistance = previous.dot(normal) - limit
        for (current in polygon) {
            val distance = current.dot(normal) - limit
            val previousInside = previousDistance <= EPSILON
            val currentInside = distance <= EPSILON
            if (previousInside != currentInside) {
                val fraction = (previousDistance / (previousDistance - distance)).coerceIn(0.0, 1.0)
                result.add(previous.add(current.subtract(previous).scale(fraction)))
            }
            if (currentInside) result.add(current)
            previous = current
            previousDistance = distance
        }
        return result
    }

    private fun coordinate(point: Vec3, axis: Int) = when (axis) { 0 -> point.x; 1 -> point.y; else -> point.z }
    private fun lower(box: AABB, axis: Int) = when (axis) { 0 -> box.minX; 1 -> box.minY; else -> box.minZ }
    private fun upper(box: AABB, axis: Int) = when (axis) { 0 -> box.maxX; 1 -> box.maxY; else -> box.maxZ }
    private fun unit(axis: Int, sign: Double) = point(axis, sign, (axis + 1) % 3, 0.0, (axis + 2) % 3, 0.0)
    private fun point(a: Int, av: Double, b: Int, bv: Double, c: Int, cv: Double): Vec3 {
        val values = DoubleArray(3)
        values[a] = av; values[b] = bv; values[c] = cv
        return Vec3(values[0], values[1], values[2])
    }
}
