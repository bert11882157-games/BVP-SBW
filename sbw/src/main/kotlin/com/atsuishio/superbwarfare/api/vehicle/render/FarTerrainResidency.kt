package com.atsuishio.superbwarfare.api.vehicle.render

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet

/** Vehicle/projectile residency is separate from completed client terrain delivery. */
internal object FarTerrainResidency {
    const val STREAMING_CHUNKS = 64
    const val PROJECTILE_CHUNKS = 256
    const val PROJECTILE_HOLD_TICKS = 20L

    fun select(required: Set<Long>, pending: Set<Long>, previous: Set<Long>,
               streamingLimit: Int): Set<Long> {
        val result = LongLinkedOpenHashSet(required)
        var remaining = streamingLimit.coerceAtLeast(0)
        // Preserve in-flight chunk loads; admitting a new camera corridor must not restart them.
        for (key in previous) if (remaining > 0 && key in pending && result.add(key)) remaining--
        for (key in pending) if (remaining > 0 && result.add(key)) remaining--
        return result
    }
}
