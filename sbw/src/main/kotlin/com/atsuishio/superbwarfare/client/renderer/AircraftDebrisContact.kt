package com.atsuishio.superbwarfare.client.renderer

import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.core.Direction
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/** Minimum separating translation for two oriented debris boxes, including edge axes. */
internal object AircraftDebrisContact {
    /** Rotating a corner can put it slightly inside the floor between sweeps.
     * Minecraft's inside-hit normal opposes travel, not the actual floor surface;
     * treating it as a wall incorrectly deletes all horizontal momentum. Recover
     * only shallow, upward-facing penetration with an independent vertical ray.
     */
    fun sweep(from: Vec3, to: Vec3, clip: (Vec3, Vec3) -> BlockHitResult?): AircraftDebrisMotion.Contact? {
        val hit = clip(from, to) ?: return null
        if (hit.isInside) {
            val support = clip(from.add(0.0, .5, 0.0), from.add(0.0, -.003, 0.0))
            if (support != null && !support.isInside && support.direction == Direction.UP)
                return AircraftDebrisMotion.Contact(support.location, Vec3(0.0, 1.0, 0.0))
        }
        return AircraftDebrisMotion.Contact(hit.location, Vec3.atLowerCornerOf(hit.direction.normal))
    }

    fun separation(a: Vec3, aq: Quaternionf, ah: Vec3, b: Vec3, bq: Quaternionf, bh: Vec3): Vec3? {
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
        return normal.scale(depth + .002)
    }
}
