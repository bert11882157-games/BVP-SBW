package com.atsuishio.superbwarfare.client.flightdisplay

import java.util.IdentityHashMap

/** Identity memoization including rejected resources. Values must not retain their keys. */
internal class IdentityFitCache<K : Any, V>(private val build: (K) -> V?) {
    private val entries = IdentityHashMap<K, V?>()
    fun get(key: K): V? {
        if (!entries.containsKey(key)) entries[key] = build(key)
        return entries[key]
    }
    fun clear() = entries.clear()
    internal val size: Int get() = entries.size
}

/** Slots may change only before world emission. A lease survives until the next frame boundary.
 * More than capacity distinct pictures in one frame intentionally remain an overflow limitation.
 */
internal class HudStillSlots<K : Any, V : Any>(
    private val capacity: Int,
    private val create: (K, Int) -> V,
    private val dispose: (V) -> Unit,
) {
    private class Entry<K, V>(val key: K, val value: V, var used: Long, var leased: Boolean)
    private val slots = mutableMapOf<Int, Entry<K, V>>()
    private var preparing = false
    private var clock = 0L
    init { require(capacity > 0) }
    fun beginFrame() {
        check(!preparing)
        preparing = true
        slots.values.forEach { it.leased = false }
    }
    fun endPreparation() { preparing = false }
    fun lease(key: K): V? = slots.values.firstOrNull { it.key === key }?.let {
        it.leased = true; it.used = ++clock; it.value
    }
    fun prepare(key: K): V? {
        check(preparing) { "HUD target mutation outside frame preparation" }
        lease(key)?.let { return it }
        val slot = (0 until capacity).firstOrNull { it !in slots }
            ?: slots.entries.filter { !it.value.leased }.minByOrNull { it.value.used }?.key
            ?: return null
        slots.remove(slot)?.let { dispose(it.value) }
        val value = create(key, slot)
        slots[slot] = Entry(key, value, ++clock, true)
        return value
    }
    /** Caller guarantees a reload/frame boundary with no pending world buffers. */
    fun clear() {
        val old = slots.values.map { it.value }
        slots.clear(); preparing = false; clock = 0
        old.forEach(dispose)
    }
    internal val size: Int get() = slots.size
}
