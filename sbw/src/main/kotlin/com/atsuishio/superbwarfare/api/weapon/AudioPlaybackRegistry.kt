package com.atsuishio.superbwarfare.api.weapon

import java.util.UUID

/** Main-thread instance ownership; sound-event names are deliberately not cancellation keys. */
class AudioPlaybackRegistry<T>(private val stopInstance: (T) -> Unit) {
    private val active = LinkedHashMap<UUID, T>()
    private val retired = LinkedHashSet<UUID>()

    fun start(id: UUID, createAndPlay: () -> T): Boolean {
        if (id in active || id in retired) return false
        if (active.size >= MAX_ACTIVE) stop(active.keys.first())
        active[id] = createAndPlay()
        return true
    }

    fun stop(id: UUID) {
        active.remove(id)?.let(stopInstance)
        retire(id)
    }

    fun retire(id: UUID) {
        active.remove(id)
        retired.add(id)
        while (retired.size > MAX_RETIRED) retired.remove(retired.first())
    }

    fun stopMatching(predicate: (T) -> Boolean) {
        active.filterValues(predicate).keys.toList().forEach(::stop)
    }

    /** Callbacks may retire entries without invalidating the traversal. */
    fun forEachActive(action: (T) -> Unit) = active.values.toList().forEach(action)

    fun clear() {
        active.values.forEach(stopInstance)
        active.clear()
        retired.clear()
    }

    internal fun activeCount() = active.size

    companion object {
        private const val MAX_ACTIVE = 512
        private const val MAX_RETIRED = 2048
    }
}
