package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/** Shared contact response for the authoritative hull piece and cosmetic fragments. */
object WreckDebrisPhysics {
    const val GROUND_DRAG = .97
    const val AIR_DRAG = .998
    const val CONTACT_SLOP = .035
    const val FRAGMENT_GROUND_TICKS = 200
    const val WRECK_LIFETIME_TICKS = 400

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

    fun settle(orientation: Quaternionf, normal: Vec3, fraction: Float, halfExtents: Vec3? = null): Quaternionf {
        val targetNormal = Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).normalize()
        val axes = listOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f))
            .map { orientation.transform(it) }
        // The shortest dimension is normal to the broadest face. Do not balance a wing
        // on its thin edge or stand a long fuselage piece on its cut end.
        val extents = halfExtents?.let { listOf(it.x, it.y, it.z) }
        val shortest = extents?.minOrNull()
        val candidates = if (extents != null && shortest != null && shortest > 0)
            axes.filterIndexed { i, _ -> extents[i] <= shortest * 1.15 } else axes
        val face = candidates.maxBy { abs(it.dot(targetNormal)) }
        if (face.dot(targetNormal) < 0) face.negate()
        val target = Quaternionf().rotationTo(face, targetNormal).mul(orientation).normalize()
        return Quaternionf(orientation).slerp(target, fraction.coerceIn(0f, 1f)).normalize()
    }

    /** Stable 6–12 second fire period, then a gradual transition to smoke. */
    fun flameStrength(age: Long, seed: Long): Float {
        val full = 120 + Math.floorMod(seed, 121L)
        return (1.0 - ((age - full).coerceAtLeast(0) / 100.0)).coerceIn(0.0, 1.0).toFloat()
    }
}
