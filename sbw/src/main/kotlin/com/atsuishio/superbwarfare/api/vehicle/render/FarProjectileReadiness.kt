package com.atsuishio.superbwarfare.api.vehicle.render

/** One nonblocking residency observation per chunk in a server tick phase, shared by all bullets. */
internal class FarProjectileReadiness<K>(private val limit: Int = 8192) {
    private val values = HashMap<K, Boolean>()
    private var tick = Long.MIN_VALUE
    private var phase = Long.MIN_VALUE
    var probes = 0
        private set

    fun begin(tick: Long, phase: Long) {
        if (this.tick == tick && this.phase == phase) return
        this.tick = tick; this.phase = phase
        values.clear(); probes = 0
    }

    fun ready(key: K, observe: () -> Boolean): Boolean {
        values[key]?.let { return it }
        if (probes >= limit) return false
        probes++
        return observe().also { values[key] = it }
    }

    fun clear() { values.clear(); tick = Long.MIN_VALUE; phase = Long.MIN_VALUE; probes = 0 }
}
