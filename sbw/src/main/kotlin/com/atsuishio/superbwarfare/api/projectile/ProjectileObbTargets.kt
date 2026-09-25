package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.OBBEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Level-local registry of OBB collision owners. Projectile entity queries test only these owners
 * (normally a few vehicles) instead of streaming every entity in the level for every projectile step.
 * Each level's array is replaced copy-on-write on its own thread; readers never lock.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object ProjectileObbTargets {
    /** Covers articulated parts (turrets, gear) and OBB/origin phase lag after the per-tick refresh. */
    internal const val ENVELOPE_MARGIN = 4.0
    private val EMPTY = emptyArray<Target>()
    private val levels = ConcurrentHashMap<Level, Array<Target>>()

    class Target internal constructor(val entity: Entity) {
        private var envelopeTick = Long.MIN_VALUE
        private var radius = -1.0

        /**
         * Conservative sphere around the current entity origin containing every OBB, refreshed once per
         * game tick. Rigid motion keeps it exact; the margin covers articulation within the tick.
         */
        fun mayOverlap(bounds: AABB): Boolean {
            val tick = entity.level().gameTime
            if (tick != envelopeTick) {
                envelopeTick = tick
                val obbs = (entity as? OBBEntity)?.getOBBs()
                radius = if (obbs.isNullOrEmpty()) -1.0 else
                    ProjectileObbTargets.envelopeRadius(entity.x, entity.y, entity.z, obbs) +
                        ProjectileObbTargets.ENVELOPE_MARGIN + 2.0 * entity.deltaMovement.length()
            }
            return radius >= 0.0 && ProjectileObbTargets.sphereIntersects(bounds, entity.x, entity.y, entity.z, radius)
        }

        /** Exact OBB test against the live boxes; a box-local sphere rejects most parts before SAT. */
        fun collides(bounds: AABB): Boolean {
            val obbs = (entity as? OBBEntity)?.getOBBs() ?: return false
            for (obb in obbs) {
                val center = obb.center
                val extents = obb.extents
                val reach = sqrt(extents.x * extents.x + extents.y * extents.y + extents.z * extents.z)
                if (!ProjectileObbTargets.sphereIntersects(bounds, center.x, center.y, center.z, reach)) continue
                if (OBB.isColliding(obb, bounds)) return true
            }
            return false
        }
    }

    @JvmStatic fun candidates(level: Level): Array<Target> = levels[level] ?: EMPTY

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun joined(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (entity !is OBBEntity || event.isCanceled) return
        levels.compute(event.level) { _, current ->
            val existing = current ?: EMPTY
            if (existing.any { it.entity === entity }) existing else existing + Target(entity)
        }
    }

    @SubscribeEvent
    fun left(event: EntityLeaveLevelEvent) {
        val entity = event.entity
        if (entity !is OBBEntity) return
        levels.computeIfPresent(event.level) { _, current ->
            val remaining = current.filter { it.entity !== entity && !it.entity.isRemoved }
            if (remaining.isEmpty()) null else remaining.toTypedArray()
        }
    }

    @SubscribeEvent
    fun unloaded(event: LevelEvent.Unload) {
        val level = event.level as? Level ?: return
        levels.remove(level)
    }

    internal fun envelopeRadius(x: Double, y: Double, z: Double, obbs: List<OBB>): Double {
        var radius = 0.0
        for (obb in obbs) {
            val dx = obb.center.x - x; val dy = obb.center.y - y; val dz = obb.center.z - z
            val e = obb.extents
            radius = max(radius, sqrt(dx * dx + dy * dy + dz * dz) + sqrt(e.x * e.x + e.y * e.y + e.z * e.z))
        }
        return radius
    }

    internal fun sphereIntersects(bounds: AABB, x: Double, y: Double, z: Double, radius: Double): Boolean {
        val dx = max(max(bounds.minX - x, x - bounds.maxX), 0.0)
        val dy = max(max(bounds.minY - y, y - bounds.maxY), 0.0)
        val dz = max(max(bounds.minZ - z, z - bounds.maxZ), 0.0)
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
