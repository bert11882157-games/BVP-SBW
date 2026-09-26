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

    private class Capture(val label: String, val seconds: Double, val batch: Boolean) {
        val startedNanos = System.nanoTime()
        val startedUtc: String = Instant.now().toString()
        var frames = FloatArray(4096)
        var count = 0
        var lastFrame = Long.MIN_VALUE
        val gcCount0 = gcCount()
        val gcMillis0 = gcMillis()
        val allocated0 = allocatedBytes()
        val entities0 = entityCounts()
        fun add(ms: Float) {
            if (count == frames.size) frames = frames.copyOf(frames.size * 2)
            frames[count++] = ms
        }
    }

    private var capture: Capture? = null
    private var poll = 0

    @SubscribeEvent
    fun onRenderTick(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        val active = capture ?: return
        val now = System.nanoTime()
        if (active.lastFrame != Long.MIN_VALUE) active.add((now - active.lastFrame) / 1_000_000f)
        active.lastFrame = now
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
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
        ClientRenderPerformanceDiagnostics.setBatchingDisabled(!batch)
        ClientRenderPerformanceDiagnostics.setProbeActive(true)
        capture = Capture(label, seconds, batch)
        Mod.LOGGER.info("BVP_PERF start {} {}s batch={}", label, seconds, batch)
    }

    private fun finish(mc: Minecraft, active: Capture) {
        capture = null
        val wallSeconds = (System.nanoTime() - active.startedNanos) / 1e9
        val counters = ClientRenderPerformanceDiagnostics.snapshot()
        val passes = ClientRenderPerformanceDiagnostics.batchPasses()
        ClientRenderPerformanceDiagnostics.setProbeActive(false)
        ClientRenderPerformanceDiagnostics.setBatchingDisabled(false)
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
                "modelLoadMs" to perMs(counters.vehicleModelLoadNanos, frames.size)),
            "renderThreadAllocatedMBPerSecond" to (allocatedBytes() - active.allocated0).let {
                if (active.allocated0 < 0 || it < 0) null else it / 1048576.0 / wallSeconds },
            "gc" to linkedMapOf("collections" to gcCount() - active.gcCount0, "millis" to gcMillis() - active.gcMillis0),
            "heapUsedMB" to Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576.0 },
            "entitiesAtStart" to active.entities0, "entitiesAtEnd" to entityCounts(),
            "levelRenderer" to runCatching { mc.levelRenderer.entityStatistics }.getOrNull(),
            "particles" to runCatching { mc.particleEngine.countParticles() }.getOrNull(),
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
