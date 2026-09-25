package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.sqrt

/** Shared contact response for the authoritative hull piece and cosmetic fragments. */
object WreckDebrisPhysics {
    const val AIR_DRAG = .998
    /** Deliberate visual overlap: a resting fragment may sink this far into the terrain surface. */
    const val CONTACT_SLOP = .18
    /** Sliding friction coefficients for metal on soil; deceleration is friction * gravity. */
    const val FUSELAGE_FRICTION = .55
    const val WING_FRICTION = .65
    /** Speed-proportional gouging loss per tick while sliding (about 26% per second). */
    const val GROUND_PLOW = .985
    /** Obstacles up to this height above the lowest support lift a sliding fragment instead of stopping it. */
    const val STEP_HEIGHT = 1.05
    /** Speed kept when a sliding fragment rides up a step. */
    const val STEP_RETENTION = .9
    /** Horizontal speed (blocks per tick) above which a grounded piece emits a scrape streak. */
    const val SCRAPE_SPEED = .02
    /** Outward separation speed given to a fragment when it breaks away from the hull. */
    const val CAPTURE_IMPULSE = .1
    /** Captures later than this after impact inherit only the momentum the wreck would still have. */
    const val LATE_CAPTURE_TICKS = 3L
    /** Toppling angular acceleration and limit, radians per tick. */
    const val TIP_ACCEL = .008f
    const val TIP_MAX = .1f
    /** A support sample this close to (or below) its terrain surface carries load. */
    const val SUPPORT_BAND = .06
    /** The center of mass must lie this far inside the support polygon to be stable. */
    const val SUPPORT_MARGIN = .03
    const val FRAGMENT_GROUND_TICKS = 200
    const val WRECK_LIFETIME_TICKS = 400

    /** Faces a fragment may come to rest on, in its captured mesh frame (x lateral, y up, z longitudinal). */
    enum class Rest(val axes: IntArray) {
        ANY(intArrayOf(0, 1, 2)),
        /** Side, top or belly; never a cut end. */
        FUSELAGE(intArrayOf(0, 1)),
        /** Flat, either face; never an edge. */
        WING(intArrayOf(1)),
    }

    fun deflect(incoming: Vec3, contactNormal: Vec3): Vec3 {
        val normal = contactNormal.normalize()
        val approach = incoming.dot(normal)
        if (approach >= 0) return incoming
        val tangent = incoming.subtract(normal.scale(approach))
        val glancing = (tangent.length() / incoming.length().coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        // Head-on impact has no artificial upward kick; grazing impact keeps sliding inertia.
        val lift = (-approach * .035 * glancing).coerceAtMost(.035)
        return tangent.scale(.82 + .12 * glancing).add(normal.scale(lift))
    }

    /** Coulomb sliding: constant deceleration opposite the horizontal motion plus a mild gouging loss. */
    fun slide(velocity: Vec3, friction: Double, gravity: Double): Vec3 {
        val speed = velocity.horizontalDistance()
        if (speed < 1e-9) return Vec3(0.0, velocity.y, 0.0)
        val next = (speed * GROUND_PLOW - friction * gravity).coerceAtLeast(0.0)
        return Vec3(velocity.x * next / speed, velocity.y, velocity.z * next / speed)
    }

    /** Horizontal speed a sliding piece still has after [ticks] on the ground. */
    fun slidingSpeedAfter(speed: Double, ticks: Long, friction: Double, gravity: Double): Double {
        var current = speed.coerceAtLeast(0.0)
        var remaining = ticks.coerceIn(0L, 1200L)
        while (remaining-- > 0 && current > 0.0)
            current = (current * AIR_DRAG * GROUND_PLOW - friction * gravity).coerceAtLeast(0.0)
        return current
    }

    /** Velocity for a fragment that breaks away [elapsed] ticks after ground impact: never stale momentum. */
    fun lateCaptureVelocity(stored: Vec3, elapsed: Long, friction: Double, gravity: Double): Vec3 {
        if (elapsed <= LATE_CAPTURE_TICKS) return stored
        val impacted = deflect(stored, Vec3(0.0, 1.0, 0.0))
        val speed = impacted.horizontalDistance()
        if (speed < 1e-9) return Vec3.ZERO
        val kept = slidingSpeedAfter(speed, elapsed, friction, gravity) / speed
        return Vec3(impacted.x * kept, 0.0, impacted.z * kept)
    }

    private fun axis(index: Int) = when (index) {
        0 -> Vector3f(1f, 0f, 0f)
        1 -> Vector3f(0f, 1f, 0f)
        else -> Vector3f(0f, 0f, 1f)
    }

    /** Local axis currently closest to [normal], ignoring sign. */
    fun verticalAxis(orientation: Quaternionf, normal: Vec3): Int {
        val up = Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).normalize()
        return (0..2).maxBy { abs(orientation.transform(axis(it)).dot(up)) }
    }

    /**
     * Local axis that should end normal to the ground: among the faces allowed by [rest], the one that
     * gives the lowest center of mass; near ties keep the face closest to the current attitude.
     */
    fun restAxis(orientation: Quaternionf, normal: Vec3, halfExtents: Vec3?, rest: Rest = Rest.ANY): Int {
        val up = Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).normalize()
        val alignment = { index: Int -> abs(orientation.transform(axis(index)).dot(up)) }
        val extents = halfExtents?.let { doubleArrayOf(it.x, it.y, it.z) }
        val lowest = extents?.let { values -> rest.axes.minOf { values[it] } }
        val candidates = if (extents != null && lowest != null && lowest > 0)
            rest.axes.filter { extents[it] <= lowest * 1.15 } else rest.axes.toList()
        return candidates.maxBy(alignment)
    }

    /** Attitude with [restAxis] aligned to [normal] by the shortest rotation. */
    fun restTarget(orientation: Quaternionf, normal: Vec3, halfExtents: Vec3?, rest: Rest = Rest.ANY): Quaternionf {
        val targetNormal = Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).normalize()
        val face = orientation.transform(axis(restAxis(orientation, normal, halfExtents, rest)))
        if (face.dot(targetNormal) < 0) face.negate()
        return Quaternionf().rotationTo(face, targetNormal).mul(orientation).normalize()
    }

    fun settle(orientation: Quaternionf, normal: Vec3, fraction: Float, halfExtents: Vec3? = null,
               rest: Rest = Rest.ANY): Quaternionf {
        // The shortest allowed dimension is normal to the broadest face. Do not balance a wing
        // on its thin edge or stand a long fuselage piece on its cut end.
        val target = restTarget(orientation, normal, halfExtents, rest)
        return Quaternionf(orientation).slerp(target, fraction.coerceIn(0f, 1f)).normalize()
    }

    /** Support edge a toppling body pivots about, and the horizontal direction its center of mass hangs over. */
    data class Overhang(val pivot: Vec3, val lean: Vec3)

    /**
     * [contacts] are load-bearing points relative to the center of mass. Returns null when the center of
     * mass projects inside their horizontal support polygon by at least [margin]. Fewer than three
     * non-collinear contacts are never stable; [hint] breaks the tie when the center is exactly above them.
     */
    fun overhang(contacts: List<Vec3>, margin: Double, hint: Vec3): Overhang? {
        if (contacts.isEmpty()) return null
        val hull = hull(contacts)
        val floorY = contacts.minOf { it.y }
        if (hull.size >= 3) {
            var inside = true
            var nearest = Double.POSITIVE_INFINITY
            var pivot = Vec3.ZERO
            var outward = Vec3.ZERO
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val ex = b.x - a.x
                val ez = b.z - a.z
                val length = sqrt(ex * ex + ez * ez)
                if (length < 1e-9) continue
                // Counter-clockwise hull: the origin is inside when it lies left of every edge.
                if ((ex * -a.z - ez * -a.x) / length < 0) inside = false
                val t = ((-a.x * ex - a.z * ez) / (length * length)).coerceIn(0.0, 1.0)
                val px = a.x + ex * t
                val pz = a.z + ez * t
                val distance = px * px + pz * pz
                if (distance < nearest) {
                    nearest = distance
                    pivot = Vec3(px, floorY, pz)
                    outward = Vec3(ez / length, 0.0, -ex / length)
                }
            }
            if (inside && sqrt(nearest) >= margin) return null
            // Outside: fall away from the nearest support point. Barely inside: over the nearest edge.
            val lean = if (!inside && nearest > 1e-8) Vec3(-pivot.x, 0.0, -pivot.z).normalize() else outward
            return Overhang(pivot, lean)
        }
        // A point or a line of support can never hold the center of mass.
        val a = hull[0]
        val b = hull.getOrElse(1) { a }
        val ex = b.x - a.x
        val ez = b.z - a.z
        val lengthSqr = ex * ex + ez * ez
        val t = if (lengthSqr < 1e-12) 0.0 else ((-a.x * ex - a.z * ez) / lengthSqr).coerceIn(0.0, 1.0)
        val pivot = Vec3(a.x + ex * t, floorY, a.z + ez * t)
        var lean = Vec3(-pivot.x, 0.0, -pivot.z)
        if (lean.lengthSqr() < 1e-8) {
            val direction = Vec3(hint.x, 0.0, hint.z).takeIf { it.lengthSqr() > 1e-8 } ?: Vec3(1.0, 0.0, 0.0)
            lean = if (lengthSqr < 1e-12) direction else {
                val edge = Vec3(ex, 0.0, ez).normalize()
                direction.subtract(edge.scale(direction.dot(edge))).takeIf { it.lengthSqr() > 1e-8 }
                    ?: Vec3(-edge.z, 0.0, edge.x)
            }
        }
        return Overhang(pivot, lean.normalize())
    }

    /** Counter-clockwise convex hull of the horizontal projection. */
    private fun hull(points: List<Vec3>): List<Vec3> {
        val sorted = points.sortedWith(compareBy<Vec3>({ it.x }, { it.z }))
        val unique = ArrayList<Vec3>()
        for (point in sorted) if (unique.none { abs(it.x - point.x) < 1e-4 && abs(it.z - point.z) < 1e-4 }) unique += point
        if (unique.size <= 2) return unique
        fun cross(o: Vec3, a: Vec3, b: Vec3) = (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x)
        val lower = ArrayList<Vec3>()
        for (point in unique) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 1e-9) lower.removeAt(lower.size - 1)
            lower += point
        }
        val upper = ArrayList<Vec3>()
        for (point in unique.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 1e-9) upper.removeAt(upper.size - 1)
            upper += point
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        val result = lower + upper
        // Collinear contacts collapse to their end points: a line of support is an edge, not an area.
        return if (result.size >= 3) result else result.distinct()
    }

    /** Stable 6–12 second fire period, then a gradual transition to smoke. */
    fun flameStrength(age: Long, seed: Long): Float {
        val full = 120 + Math.floorMod(seed, 121L)
        return (1.0 - ((age - full).coerceAtLeast(0) / 100.0)).coerceIn(0.0, 1.0).toFloat()
    }
}
