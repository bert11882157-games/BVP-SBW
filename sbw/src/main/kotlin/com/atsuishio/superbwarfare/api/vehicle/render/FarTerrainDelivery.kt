package com.atsuishio.superbwarfare.api.vehicle.render

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet

/** Current sightlines precede prediction and spare cover in both ticket and packet queues. */
internal object FarTerrainDelivery {
    const val NEW_TICKETS_PER_TICK = 8
    const val PACKETS_PER_TICK = 8
    const val BYTES_PER_TICK = 2 * 1024 * 1024

    fun prioritize(required: Set<Long>, desired: Set<Long>): Set<Long> = LongLinkedOpenHashSet().also {
        it.addAll(required.filter { key -> key in desired })
        it.addAll(desired)
    }

    /** Add optional cells individually; a large prediction must not reject usable spare capacity. */
    fun appendWithinBudget(current: Set<Long>, addition: Iterable<Long>, shared: Set<Long>,
                           globalUsed: Int, maxNewChunks: Int = FarTerrainPolicy.MAX_CHUNKS): Set<Long> {
        val result = LongLinkedOpenHashSet(current)
        var extra = result.count { it !in shared }
        var added = 0
        for (key in addition) {
            if (added >= maxNewChunks) break
            if (key in result || result.size >= FarTerrainPolicy.MAX_CHUNKS) continue
            if (key !in shared && globalUsed + extra >= FarTerrainPolicy.GLOBAL_CHUNKS) continue
            result.add(key)
            added++
            if (key !in shared) extra++
        }
        return result
    }
}
