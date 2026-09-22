package com.atsuishio.superbwarfare.api.internal

import java.util.LinkedHashMap

/** Mutation-rare ordered registry with stable, lock-free read snapshots. */
internal class OrderedProviderRegistry<K, V> {
    @Volatile private var entries: Map<K, V> = emptyMap()

    fun register(key: K, value: V) = synchronized(this) {
        entries = LinkedHashMap(entries).apply { this[key] = value }
    }

    fun unregister(key: K): Boolean = synchronized(this) {
        (key in entries).also { removed -> if (removed) entries = entries - key }
    }

    operator fun get(key: K): V? = entries[key]

    fun snapshot(): Map<K, V> = entries
}
