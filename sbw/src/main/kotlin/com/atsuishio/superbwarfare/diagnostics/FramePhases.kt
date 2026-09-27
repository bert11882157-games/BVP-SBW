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
    /** Flywheel mesh-pool full re-uploads (counted by a BVP mixin), this frame. */
    @JvmField var meshUploads = 0
    @JvmField var meshUploadNanos = 0L

    fun reset() {
        entitiesNanos = 0; particlesNanos = 0; tickNanos = 0; ticks = 0; renderNanos = 0; joins = 0; meshUploads = 0; meshUploadNanos = 0
    }
}
