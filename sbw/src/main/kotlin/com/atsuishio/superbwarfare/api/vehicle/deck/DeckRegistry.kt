package com.atsuishio.superbwarfare.api.vehicle.deck

import com.atsuishio.superbwarfare.Mod
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Level-local list of deck owners (normally none, a carrier or two). Collision and probe queries test only these,
 * by their turned plan, so a 270-block hull is found from anywhere along it, not only near its entity section.
 * Copy-on-write arrays per level: readers on the server and client threads never lock.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object DeckRegistry {
    private val EMPTY = emptyArray<Entity>()
    private val levels = ConcurrentHashMap<Level, Array<Entity>>()

    @JvmStatic fun providers(level: Level): Array<Entity> = levels[level] ?: EMPTY

    @JvmStatic fun isEmpty(level: Level): Boolean = (levels[level] ?: EMPTY).isEmpty()

    /** Deck owners whose surface envelope meets [bounds]. */
    @JvmStatic fun near(level: Level, bounds: AABB): List<Entity> {
        val all = levels[level] ?: return emptyList()
        if (all.isEmpty()) return emptyList()
        var out: ArrayList<Entity>? = null
        for (entity in all) {
            if (entity.isRemoved) continue
            val surface = (entity as DeckSurfaceEntity).deckSurface() ?: continue
            if (!DeckPose.of(entity).envelope(surface).intersects(bounds)) continue
            (out ?: ArrayList<Entity>(2).also { out = it }).add(entity)
        }
        return out ?: emptyList()
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun joined(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (entity !is DeckSurfaceEntity || event.isCanceled) return
        levels.compute(event.level) { _, current ->
            val existing = current ?: EMPTY
            if (existing.any { it === entity }) existing else existing + entity
        }
    }

    @SubscribeEvent
    fun left(event: EntityLeaveLevelEvent) {
        val entity = event.entity
        if (entity !is DeckSurfaceEntity) return
        levels.computeIfPresent(event.level) { _, current ->
            val kept = current.filter { it !== entity }
            if (kept.isEmpty()) null else kept.toTypedArray()
        }
        DeckCarry.forget(entity)
    }

    @SubscribeEvent
    fun unloaded(event: LevelEvent.Unload) {
        val level = event.level as? Level ?: return
        levels.remove(level)
    }
}
