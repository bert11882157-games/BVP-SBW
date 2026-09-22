package com.atsuishio.superbwarfare.api.vehicle.render

/** Client frame lifetime within one authenticated far-vehicle session. */
internal class FarTerrainFrameGate {
    var revision = -1L
        private set
    var acknowledged = -1L
        private set
    var committed = -1L
        private set
    private var lastActivity = 0L

    fun clear() {
        revision = -1
        acknowledged = -1
        committed = -1
        lastActivity = 0
    }

    /** Retained frames remain eligible while a newer session plan is acknowledged. */
    fun plan(next: Long, tick: Long): Boolean {
        if (next < 0 || next < revision) return false
        lastActivity = tick
        if (next == revision) return false
        revision = next
        acknowledged = -1
        return true
    }

    fun acknowledge() { acknowledged = revision }

    fun acceptFrame(frameRevision: Long, tick: Long): Boolean {
        if (revision < 0 || frameRevision != revision) return false
        // The server may reuse previously acknowledged, unchanged chunks before the next ACK.
        lastActivity = tick
        return true
    }

    fun commit(frameRevision: Long) {
        if (revision >= 0 && frameRevision == revision) committed = frameRevision
    }

    fun age(tick: Long): Long = tick - lastActivity

    fun ready(tick: Long): Boolean = committed >= 0 && tick >= lastActivity
}
