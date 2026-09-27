package com.atsuishio.superbwarfare.diagnostics

/**
 * Perf probe only: per-frame phase accumulators filled by [PerfProbe] (client tick, render) and the frame-phase
 * mixins (entity tick, particle tick). Read and reset once per frame at the frame boundary. Render thread only.
 */
object FramePhases {
    @JvmField var active = false
    @JvmField var entitiesStart = 0L
    @JvmField var entitiesNanos = 0L
    @JvmField var particlesStart = 0L
    @JvmField var particlesNanos = 0L
    @JvmField var tickStart = 0L
    @JvmField var tickNanos = 0L
    @JvmField var ticks = 0
    @JvmField var renderStart = 0L
    @JvmField var renderNanos = 0L
    @JvmField var joins = 0

    fun reset() {
        entitiesNanos = 0; particlesNanos = 0; tickNanos = 0; ticks = 0; renderNanos = 0; joins = 0
    }
}
