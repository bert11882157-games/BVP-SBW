package com.atsuishio.superbwarfare.client.renderer

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/** Minimum separating translation for two oriented debris boxes, including edge axes. */
internal object AircraftDebrisContact {
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
