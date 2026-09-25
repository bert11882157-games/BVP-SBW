package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.core.Direction
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/** Minimum separating translation for two oriented debris boxes, including edge axes. */
internal object AircraftDebrisContact {
    /** Support probes tolerate the deliberate floor overlap without snagging a slide. */
    fun relaxedSweep(from: Vec3, to: Vec3, probe: Double = 1.0,
                     clip: (Vec3, Vec3) -> BlockHitResult?): AircraftDebrisMotion.Contact? {
        val lift = Vec3(0.0, WreckDebrisPhysics.CONTACT_SLOP + .005, 0.0)
        val hit = sweep(from.add(lift), to.add(lift), probe, clip) ?: return null
        return if (hit.normal.y > .5) hit else hit.copy(position = hit.position.subtract(lift))
    }

    /** Rotating a corner can put it inside the floor between sweeps. Minecraft's inside-hit normal
     * opposes travel, not the actual surface; treating it as a wall pins the fragment in place.
     * Recover with an independent vertical ray from [probe] above; an embedded sample without a
     * reachable surface is skipped, never a wall, and the depenetration pass lifts the piece.
     */
    fun sweep(from: Vec3, to: Vec3, probe: Double = 1.0,
              clip: (Vec3, Vec3) -> BlockHitResult?): AircraftDebrisMotion.Contact? {
        val hit = clip(from, to) ?: return null
        if (hit.isInside) {
            val support = clip(from.add(0.0, probe.coerceAtLeast(.5), 0.0), from.add(0.0, -.003, 0.0))
            if (support != null && !support.isInside && support.direction == Direction.UP)
                return AircraftDebrisMotion.Contact(support.location, Vec3(0.0, 1.0, 0.0), support.location)
            return null
        }
        return AircraftDebrisMotion.Contact(hit.location, Vec3.atLowerCornerOf(hit.direction.normal), hit.location)
    }

    /** Downward surface query: the top of the first upward face, [AircraftDebrisMotion.BURIED] inside a solid. */
    fun surface(from: Vec3, depth: Double, clip: (Vec3, Vec3) -> BlockHitResult?): Double? {
        val hit = clip(from, from.add(0.0, -depth, 0.0)) ?: return null
        if (hit.isInside) return AircraftDebrisMotion.BURIED
        return if (hit.direction == Direction.UP) hit.location.y else null
    }

    fun separation(a: Vec3, aq: Quaternionf, ah: Vec3, b: Vec3, bq: Quaternionf, bh: Vec3,
                   tolerance: Double = 0.0): Vec3? {
        if (a.distanceToSqr(b) > (ah.length() + bh.length()).let { it * it }) return null
        fun axes(q: Quaternionf) = listOf(Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),Vector3f(0f,0f,1f))
            .map { q.transform(it); Vec3(it.x.toDouble(),it.y.toDouble(),it.z.toDouble()) }
        val aa=axes(aq); val ba=axes(bq); val delta=a.subtract(b)
        var depth=Double.POSITIVE_INFINITY; var normal=Vec3.ZERO
        for (candidate in aa + ba + aa.flatMap { x -> ba.map { x.cross(it) } } + listOf(Vec3(1.0,0.0,0.0),Vec3(0.0,0.0,1.0))) {
            if (candidate.lengthSqr() < 1e-10) continue
            val axis=candidate.normalize()
            fun extent(basis: List<Vec3>, half: Vec3) = abs(axis.dot(basis[0]))*half.x +
                abs(axis.dot(basis[1]))*half.y + abs(axis.dot(basis[2]))*half.z
            val overlap=extent(aa,ah)+extent(ba,bh)-abs(delta.dot(axis))
            if (overlap <= 1e-6) return null
            // Resolve sideways so overlapping fragments cannot push one another below terrain.
            if (abs(axis.y) < 1e-6 && overlap < depth) { depth=overlap; normal=axis.scale(if(delta.dot(axis)<0) -1.0 else 1.0) }
        }
        // Slight interpenetration between fragments is tolerated like the terrain slop.
        if (depth <= tolerance || normal == Vec3.ZERO) return null
        return normal.scale(depth - tolerance + .002)
    }
}
