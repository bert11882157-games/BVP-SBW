package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.projectile.Projectile
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): measures client frame times for a window asked for by
 * external orchestration. `diagnostic-perf.request` in the game directory holds `<label> <seconds> [batch=on|off]`;
 * the result goes to `logs/bvp-perf/<label>.json`: frame-time percentiles and FPS, the render counters of
 * [ClientRenderPerformanceDiagnostics] over the same window (vehicle render CPU time, mesh draws, batched passes,
 * track link rebuilds, tracers), render-thread allocation, GC, and what was in the level. `batch=off` switches the
 * batched direct-VBO draw off for the window (an A/B comparison in one session). Inert in every normal game.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object PerfProbe {
    private const val REQUEST = "diagnostic-perf.request"
    private const val POLL_TICKS = 4
    private const val PHASES = 8
    private val PHASE_NAMES = listOf("clientTickMs", "ticks", "entityTickMs", "particleTickMs", "renderMs", "entitiesJoined",
        "meshPoolUploads", "meshPoolUploadMs")

    private class Capture(val label: String, val seconds: Double, val batch: Boolean) {
        val startedNanos = System.nanoTime()
        val startedUtc: String = Instant.now().toString()
        var frames = FloatArray(4096)
        /** Per frame: client tick ms, ticks run, entity tick ms, particle tick ms, render ms, entities joined. */
        var phases = FloatArray(4096 * PHASES)
        var count = 0
        var lastFrame = Long.MIN_VALUE
        val gcCount0 = gcCount()
        val gcMillis0 = gcMillis()
        val allocated0 = allocatedBytes()
        val entities0 = entityCounts()
        fun add(ms: Float) {
            if (count == frames.size) { frames = frames.copyOf(frames.size * 2); phases = phases.copyOf(frames.size * PHASES) }
            val at = count * PHASES
            phases[at] = FramePhases.tickNanos / 1e6f
            phases[at + 1] = FramePhases.ticks.toFloat()
            phases[at + 2] = FramePhases.entitiesNanos / 1e6f
            phases[at + 3] = FramePhases.particlesNanos / 1e6f
            phases[at + 4] = FramePhases.renderNanos / 1e6f
            phases[at + 5] = FramePhases.joins.toFloat()
            phases[at + 6] = FramePhases.meshUploads.toFloat()
            phases[at + 7] = FramePhases.meshUploadNanos / 1e6f
            frames[count++] = ms
        }
    }

    private var capture: Capture? = null
    private var fxNanos0 = 0L
    private var poll = 0

    @SubscribeEvent
    fun onRenderTick(event: TickEvent.RenderTickEvent) {
        val active = capture ?: return
        val now = System.nanoTime()
        if (event.phase == TickEvent.Phase.END) {
            FramePhases.renderNanos += now - FramePhases.renderStart
            return
        }
        // Frame boundary. The interval since the previous one holds the previous frame's render and buffer swap,
        // then this frame's packets and client ticks.
        if (active.lastFrame != Long.MIN_VALUE) active.add((now - active.lastFrame) / 1_000_000f)
        FramePhases.reset()
        active.lastFrame = now
        FramePhases.renderStart = now
    }

    @SubscribeEvent
    fun onEntityJoin(event: net.minecraftforge.event.entity.EntityJoinLevelEvent) {
        if (FramePhases.active && event.level.isClientSide) FramePhases.joins++
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (FramePhases.active) {
            if (event.phase == TickEvent.Phase.START) { FramePhases.tickStart = System.nanoTime(); return }
            FramePhases.tickNanos += System.nanoTime() - FramePhases.tickStart
            FramePhases.ticks++
        }
        if (event.phase != TickEvent.Phase.END) return
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        val mc = Minecraft.getInstance()
        val active = capture
        if (active != null) {
            if ((System.nanoTime() - active.startedNanos) / 1e9 >= active.seconds) finish(mc, active)
            return
        }
        if (++poll < POLL_TICKS) return
        poll = 0
        val file = File(mc.gameDirectory, REQUEST)
        if (!file.isFile) return
        val parts = runCatching { file.readText().trim().split(Regex("\\s+")) }.getOrDefault(emptyList())
        file.delete()
        val label = parts.getOrNull(0)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,60}")) } ?: return
        val seconds = parts.getOrNull(1)?.toDoubleOrNull()?.coerceIn(1.0, 300.0) ?: 10.0
        val batch = parts.none { it == "batch=off" }
        com.atsuishio.superbwarfare.client.particle.FxLights.enabled = parts.none { it == "fx=off" }
        fxNanos0 = com.atsuishio.superbwarfare.client.particle.FxLights.renderNanos
        ParticleProbe.hideParticles = parts.any { it == "particles=off" }
        ParticleProbe.hideBlasts = parts.any { it == "blasts=off" }
        ParticleProbe.start()
        // vanilla's F3+L profiler (10 s, section times incl. GPU wait) saved under debug/profiling
        if (parts.any { it == "mcprof=on" }) {
            val started = mc.debugClientMetricsStart { Mod.LOGGER.info("BVP_PERF mcprof {}", it.string) }
            Mod.LOGGER.info("BVP_PERF mcprof started={}", started)
        }
        ClientRenderPerformanceDiagnostics.setBatchingDisabled(!batch)
        ClientRenderPerformanceDiagnostics.setProbeActive(true)
        capture = Capture(label, seconds, batch)
        FramePhases.reset()
        FramePhases.active = true
        Mod.LOGGER.info("BVP_PERF start {} {}s batch={}", label, seconds, batch)
    }

    private fun finish(mc: Minecraft, active: Capture) {
        capture = null
        FramePhases.active = false
        val breakdown = frameBreakdown(active)
        val wallSeconds = (System.nanoTime() - active.startedNanos) / 1e9
        val counters = ClientRenderPerformanceDiagnostics.snapshot()
        val passes = ClientRenderPerformanceDiagnostics.batchPasses()
        ClientRenderPerformanceDiagnostics.setProbeActive(false)
        ClientRenderPerformanceDiagnostics.setBatchingDisabled(false)
        val fxEnabled = com.atsuishio.superbwarfare.client.particle.FxLights.enabled
        val hidden = listOfNotNull("particles".takeIf { ParticleProbe.hideParticles }, "blasts".takeIf { ParticleProbe.hideBlasts })
        ParticleProbe.hideParticles = false; ParticleProbe.hideBlasts = false
        com.atsuishio.superbwarfare.client.particle.FxLights.enabled = true
        val frames = active.frames.copyOf(active.count).also { it.sort() }
        fun pct(p: Double) = if (frames.isEmpty()) 0f else frames[((p * frames.size).toInt()).coerceIn(0, frames.size - 1)]
        val totalMs = frames.sum().toDouble()
        val report = linkedMapOf<String, Any?>(
            "schema" to 1, "label" to active.label, "startedUtc" to active.startedUtc,
            "completedUtc" to Instant.now().toString(), "wallSeconds" to wallSeconds, "batched" to active.batch,
            "frames" to frames.size,
            "fps" to (if (totalMs > 0) frames.size * 1000.0 / totalMs else 0.0),
            "frameMs" to linkedMapOf("mean" to (if (frames.isEmpty()) 0.0 else totalMs / frames.size),
                "p50" to pct(0.50), "p90" to pct(0.90), "p95" to pct(0.95), "p99" to pct(0.99),
                "p999" to pct(0.999), "max" to (frames.lastOrNull() ?: 0f)),
            "framesOver16_7ms" to frames.count { it > 16.7f }, "framesOver33ms" to frames.count { it > 33.3f },
            "lows" to linkedMapOf(
                "onePercentLowFps" to lowFps(frames, 0.01), "pointOnePercentLowFps" to lowFps(frames, 0.001),
                "fivePercentLowFps" to lowFps(frames, 0.05)),
            "frameBreakdown" to breakdown,
            "perFrame" to linkedMapOf(
                "vehicleRenders" to per(counters.vehicleRenders, frames.size),
                "vehicleRenderMs" to perMs(counters.vehicleRenderNanos, frames.size),
                "meshDraws" to per(counters.polyMeshDrawCalls, frames.size),
                "batchedPasses" to per(passes, frames.size),
                "vboUploads" to per(counters.polyMeshUploads, frames.size),
                "trackLinkRebuilds" to per(counters.linksTransformRebuilds, frames.size),
                "trackLinksEvaluated" to per(counters.linksEvaluated, frames.size),
                "trackMs" to perMs(counters.linksTransformNanos, frames.size),
                "tracerRenderMs" to perMs(counters.tracerRenderNanos, frames.size),
                "tracerDiscoveryMs" to perMs(counters.tracerDiscoveryNanos, frames.size),
                "tracerBeams" to per(counters.tracerBeamsDrawn, frames.size),
                "ccipMs" to perMs(counters.ccipSampleNanos, frames.size),
                "modelLoadMs" to perMs(counters.vehicleModelLoadNanos, frames.size),
                "fxLightMs" to perMs(com.atsuishio.superbwarfare.client.particle.FxLights.renderNanos - fxNanos0, frames.size)),
            "fxLights" to linkedMapOf("enabled" to fxEnabled,
                "alive" to com.atsuishio.superbwarfare.client.particle.FxLights.lastLights,
                "groundQuads" to com.atsuishio.superbwarfare.client.particle.FxLights.lastPoolQuads),
            "renderThreadAllocatedMBPerSecond" to (allocatedBytes() - active.allocated0).let {
                if (active.allocated0 < 0 || it < 0) null else it / 1048576.0 / wallSeconds },
            "gc" to linkedMapOf("collections" to gcCount() - active.gcCount0, "millis" to gcMillis() - active.gcMillis0),
            "heapUsedMB" to Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576.0 },
            "entitiesAtStart" to active.entities0, "entitiesAtEnd" to entityCounts(),
            "levelRenderer" to runCatching { mc.levelRenderer.entityStatistics }.getOrNull(),
            "particles" to runCatching { mc.particleEngine.countParticles() }.getOrNull(),
            "hidden" to hidden,
            "drawnParticlesPerFrame" to ParticleProbe.finish(frames.size),
            "window" to "${mc.window.width}x${mc.window.height}",
            "renderDistance" to mc.options.renderDistance().get(),
        )
        try {
            val dir = File(mc.gameDirectory, "logs/bvp-perf").also { it.mkdirs() }
            File(dir, active.label + ".json").writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report))
        } catch (failure: Exception) {
            Mod.LOGGER.error("BVP_PERF write failed", failure)
        }
        Mod.LOGGER.info("BVP_PERF done {} fps={} p50={} p99={} frames={}", active.label,
            "%.1f".format(report["fps"] as Double), pct(0.5), pct(0.99), frames.size)
    }

    /** FPS over the slowest [share] of frames (mean of their times), the usual "1% low". */
    private fun lowFps(sorted: FloatArray, share: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val n = (sorted.size * share).toInt().coerceAtLeast(1)
        var sum = 0.0
        for (i in sorted.size - n until sorted.size) sum += sorted[i]
        return if (sum > 0) n * 1000.0 / sum else 0.0
    }

    /**
     * Mean phase times for typical frames (the middle fifth), slow frames (slowest 10%) and the worst 1%, so the
     * frames behind the lows can be told apart from the median ones. `otherMs` is the rest of the interval: packets,
     * the buffer swap / GPU wait, and anything outside the tick and render events.
     */
    private fun frameBreakdown(active: Capture): Map<String, Any> {
        val n = active.count
        if (n == 0) return emptyMap()
        val order = (0 until n).sortedBy { active.frames[it] }
        fun band(from: Double, to: Double): Map<String, Any> {
            val a = (n * from).toInt().coerceIn(0, n - 1)
            val b = (n * to).toInt().coerceIn(a + 1, n)
            val idx = order.subList(a, b)
            val out = linkedMapOf<String, Any>("frames" to idx.size, "frameMs" to idx.map { active.frames[it].toDouble() }.average())
            var accounted = 0.0
            for (k in 0 until PHASES) {
                val mean = idx.map { active.phases[it * PHASES + k].toDouble() }.average()
                out[PHASE_NAMES[k]] = mean
                if (k == 0 || k == 4) accounted += mean
            }
            out["otherMs"] = (out["frameMs"] as Double) - accounted
            return out
        }
        var uploads = 0.0; var uploadMs = 0.0
        for (i in 0 until n) { uploads += active.phases[i * PHASES + 6]; uploadMs += active.phases[i * PHASES + 7] }
        return linkedMapOf("median" to band(0.4, 0.6), "slowest10pct" to band(0.9, 1.0), "worst1pct" to band(0.99, 1.0),
            "meshPoolUploadsTotal" to uploads, "meshPoolUploadMsTotal" to uploadMs)
    }

    private fun per(value: Long, frames: Int) = if (frames == 0) 0.0 else value.toDouble() / frames
    private fun perMs(nanos: Long, frames: Int) = if (frames == 0) 0.0 else nanos / 1e6 / frames

    private fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun gcMillis() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

    /** Bytes allocated so far by the calling (client/render) thread, or -1 when the JVM does not say. */
    private fun allocatedBytes(): Long = runCatching {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled)
            bean.getThreadAllocatedBytes(Thread.currentThread().id) else -1L
    }.getOrDefault(-1L)

    private fun entityCounts(): Map<String, Int> {
        val level = Minecraft.getInstance().level ?: return emptyMap()
        var vehicles = 0; var projectiles = 0; var other = 0
        for (entity in level.entitiesForRendering()) when (entity) {
            is VehicleEntity -> vehicles++
            is Projectile -> projectiles++
            else -> other++
        }
        return linkedMapOf("vehicles" to vehicles, "projectiles" to projectiles, "other" to other)
    }
}
