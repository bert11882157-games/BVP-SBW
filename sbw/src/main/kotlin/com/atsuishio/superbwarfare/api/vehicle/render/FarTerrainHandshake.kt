package com.atsuishio.superbwarfare.api.vehicle.render

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/** Revision handshake for snapshots. Current vehicle subscriptions use empty terrain plans.
 * Acknowledgements cannot grant chunks or resurrect a superseded plan. */
internal class FarTerrainHandshake(val token: String, val dimension: String) {
    var revision = 0L
        private set
    var acknowledged = -1L
        private set
    var radius = 0
        private set
    var desired: Set<Long> = emptySet()
        private set
    val sent = LongOpenHashSet()
    private val readyChunks = LongOpenHashSet()

    fun update(range: Int, chunks: Set<Long>, invalidated: Set<Long>): Boolean {
        require(range in FarTerrainPolicy.MIN_ACQUISITION_RADIUS..FarTerrainPolicy.MAX_ACQUISITION_RADIUS &&
            chunks.size <= FarTerrainPolicy.MAX_CHUNKS && chunks.containsAll(invalidated))
        if (range == radius && desired == chunks && invalidated.isEmpty()) return false
        revision++
        acknowledged = -1
        sent.retainAll(chunks)
        sent.removeAll(invalidated)
        // A revision changes the subscription, not the contents of every retained chunk.
        // Preserve proof for unchanged terrain so an unrelated update cannot hide all vehicles.
        readyChunks.retainAll(chunks)
        readyChunks.removeAll(invalidated)
        desired = LongLinkedOpenHashSet(chunks)
        radius = range
        return true
    }

    fun delivered(revision: Long, chunk: Long): Boolean {
        if (revision != this.revision || chunk !in desired) return false
        sent.add(chunk)
        return true
    }

    fun acknowledge(token: String, dimension: String, revision: Long, chunks: Set<Long> = desired): Boolean {
        if (token != this.token || dimension != this.dimension || revision != this.revision ||
            !desired.containsAll(chunks) || !sent.containsAll(chunks)) return false
        readyChunks.clear()
        readyChunks.addAll(chunks)
        acknowledged = revision
        return true
    }

    fun readyFor(chunks: Set<Long>): Boolean = radius > 0 && readyChunks.containsAll(chunks)
    fun ready(): Boolean = readyFor(desired)
}
