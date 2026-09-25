package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Line-of-sight fraction between a detonation point and a target; blocks are the only cover. */
object BlastExposure {
    private const val VEHICLE_SAMPLES_PER_AXIS = 3

    /**
     * Vanilla's sampling for ordinary entities; a bounded 3x3x3 grid for vehicles, whose large boxes
     * would otherwise cost thousands of ray casts.
     */
    @JvmStatic
    fun seenFraction(level: Level, center: Vec3, entity: Entity): Double =
        if (entity is VehicleEntity) sampled(level, center, entity.boundingBox, VEHICLE_SAMPLES_PER_AXIS)
        else Explosion.getSeenPercent(center, entity).toDouble()

    @JvmStatic
    fun sampled(level: Level, center: Vec3, box: AABB, perAxis: Int): Double {
        val steps = perAxis.coerceIn(1, 5)
        var clear = 0
        var total = 0
        for (i in 0 until steps) for (j in 0 until steps) for (k in 0 until steps) {
            val fx = if (steps == 1) 0.5 else i / (steps - 1.0)
            val fy = if (steps == 1) 0.5 else j / (steps - 1.0)
            val fz = if (steps == 1) 0.5 else k / (steps - 1.0)
            // Pull samples slightly inside the box so resting contact with terrain does not count as cover.
            val point = Vec3(
                box.minX + (box.maxX - box.minX) * (0.05 + 0.9 * fx),
                box.minY + (box.maxY - box.minY) * (0.05 + 0.9 * fy),
                box.minZ + (box.maxZ - box.minZ) * (0.05 + 0.9 * fz),
            )
            total++
            if (level.clip(ClipContext(point, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).type ==
                HitResult.Type.MISS) clear++
        }
        return if (total == 0) 0.0 else clear.toDouble() / total
    }
}
