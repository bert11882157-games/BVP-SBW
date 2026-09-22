package com.atsuishio.superbwarfare.api.vehicle.render

/** Bounded server-thread leases. Speculation cannot reserve capacity against a current collision sweep. */
internal class FarProjectileResidency<K>(
    private val capacity: Int = 256,
    private val speculativeLimit: Int = 64,
    private val holdTicks: Long = 20
) {
    private data class Lease(var expires: Long, var requiredUntil: Long, var touched: Long)
    private val leases = LinkedHashMap<K, Lease>()
    private var expiredAt = Long.MIN_VALUE

    val size get() = leases.size
    fun keys(): Set<K> = leases.keys.toSet()
    fun removeIf(predicate: (K) -> Boolean) { leases.keys.removeIf(predicate) }
    fun clear() { leases.clear(); expiredAt = Long.MIN_VALUE }
    fun expire(now: Long) {
        if (expiredAt == now) return
        leases.entries.removeIf { it.value.expires < now }
        expiredAt = now
    }
    fun required(key: K, now: Long): Boolean = (leases[key]?.requiredUntil ?: Long.MIN_VALUE) >= now

    fun reserve(keys: Set<K>, now: Long, imminent: Boolean): Boolean {
        expire(now)
        if (keys.isEmpty() || keys.size > capacity) return false
        val missing = keys.count { it !in leases }
        // Bullets in a burst commonly share the same corridor. Renew existing leases directly;
        // their requests must not repeatedly scan/sort the entire global pool each tick.
        if (missing == 0) {
            for (key in keys) renew(leases.getValue(key), now, imminent)
            return true
        }
        val victims = if (imminent) {
            val needed = (leases.size + missing - capacity).coerceAtLeast(0)
            val available = if (needed == 0) emptyList() else leases.entries.asSequence()
                .filter { it.key !in keys && it.value.requiredUntil < now }
                .sortedWith(compareBy<Map.Entry<K, Lease>> { it.value.requiredUntil }.thenBy { it.value.touched })
                .take(needed).map { it.key }.toList()
            if (available.size < needed) return false
            available
        } else {
            // Do not refresh stale required leases through speculative requests. In particular,
            // failed admission must not keep a partial corridor alive forever at full capacity.
            val speculative = leases.values.count { it.requiredUntil == Long.MIN_VALUE }
            if (speculative + missing > speculativeLimit) return false
            val needed = (leases.size + missing - capacity).coerceAtLeast(0)
            val obsolete = if (needed == 0) emptyList() else leases.entries.asSequence()
                .filter { it.key !in keys && it.value.requiredUntil != Long.MIN_VALUE && it.value.requiredUntil < now }
                .sortedBy { it.value.touched }.take(needed).map { it.key }.toList()
            if (obsolete.size < needed) return false
            obsolete
        }
        victims.forEach(leases::remove)
        for (key in keys) {
            val lease = leases[key]
            if (lease == null) leases[key] = Lease(now + holdTicks, if (imminent) now + 2 else Long.MIN_VALUE, now)
            else renew(lease, now, imminent)
        }
        return true
    }

    private fun renew(lease: Lease, now: Long, imminent: Boolean) {
        if (imminent) {
            lease.expires = now + holdTicks
            lease.requiredUntil = now + 2
            lease.touched = now
        } else if (lease.requiredUntil == Long.MIN_VALUE) lease.expires = now + holdTicks
    }
}
