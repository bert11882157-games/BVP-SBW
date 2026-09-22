package com.atsuishio.superbwarfare.entity.vehicle.base

/** Copy-on-change publication; live mutable values must never escape into the snapshot. */
internal object WeaponSnapshotPublisher {
    fun <T> changedSnapshot(live: Map<String, T>, published: Map<String, T>, copy: (T) -> T): Map<String, T>? {
        var changed: MutableMap<String, T>? = null
        for ((name, value) in live) {
            if (value == published[name]) continue
            val next = changed ?: published.toMutableMap().also { changed = it }
            next[name] = copy(value)
        }
        if (published.keys != live.keys) {
            val next = changed ?: published.toMutableMap().also { changed = it }
            next.keys.retainAll(live.keys)
        }
        return changed?.let(java.util.Collections::unmodifiableMap)
    }
}
