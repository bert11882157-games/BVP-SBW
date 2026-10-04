package com.atsuishio.superbwarfare.api.vehicle.deck

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A deck owner's placement: world origin and yaw (degrees, Minecraft convention). Its data frame maps to the
 * world as the vehicle renderer draws it: local +Z is the facing direction (-sin yaw, cos yaw), local +X its left
 * (cos yaw, sin yaw). Decks never pitch or roll.
 */
class DeckPose(val x: Double, val y: Double, val z: Double, val yaw: Float) {
    private val c = cos(Math.toRadians(yaw.toDouble()))
    private val s = sin(Math.toRadians(yaw.toDouble()))

    fun worldX(localX: Double, localZ: Double): Double = x + localX * c - localZ * s
    fun worldZ(localX: Double, localZ: Double): Double = z + localX * s + localZ * c
    fun localX(worldX: Double, worldZ: Double): Double = (worldX - x) * c + (worldZ - z) * s
    fun localZ(worldX: Double, worldZ: Double): Double = -(worldX - x) * s + (worldZ - z) * c

    /** World box holding the whole surface (plan corners turned, every column's height). */
    fun envelope(surface: DeckSurface): AABB {
        var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY
        var z0 = Double.POSITIVE_INFINITY; var z1 = Double.NEGATIVE_INFINITY
        for (lx in doubleArrayOf(surface.minX, surface.maxX)) for (lz in doubleArrayOf(surface.minZ, surface.maxZ)) {
            val wx = worldX(lx, lz); val wz = worldZ(lx, lz)
            x0 = min(x0, wx); x1 = max(x1, wx); z0 = min(z0, wz); z1 = max(z1, wz)
        }
        return AABB(x0, y + surface.minBottom, z0, x1, y + surface.maxTop, z1)
    }

    fun sameAs(other: DeckPose): Boolean =
        abs(x - other.x) < 1e-7 && abs(y - other.y) < 1e-7 && abs(z - other.z) < 1e-7 && abs(yaw - other.yaw) < 1e-5f

    companion object {
        @JvmStatic fun of(entity: Entity): DeckPose = DeckPose(entity.x, entity.y, entity.z, entity.yRot)
    }
}
