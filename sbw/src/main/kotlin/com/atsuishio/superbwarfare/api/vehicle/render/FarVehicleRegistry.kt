package com.atsuishio.superbwarfare.api.vehicle.render

import java.util.Collections
import java.util.IdentityHashMap

/** Chunk visibility can end tracking without removing an entity or producing another join event. */
internal class FarVehicleRegistry<T : Any>(
    private val capacity: Int,
    private val removed: (T) -> Boolean,
    private val accessible: (T) -> Boolean,
) {
    private val entities = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    val size: Int get() = entities.size

    fun joined(entity: T) {
        if (!removed(entity) && entities.size < capacity) entities.add(entity)
    }

    fun left(entity: T) {
        if (removed(entity)) entities.remove(entity)
    }

    fun visible(): List<T> {
        entities.removeIf(removed)
        return entities.filter(accessible)
    }

    fun clear() = entities.clear()
}
