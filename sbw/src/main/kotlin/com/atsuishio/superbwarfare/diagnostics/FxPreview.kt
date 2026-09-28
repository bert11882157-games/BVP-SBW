package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.io.File

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): shows engine effects on parked aircraft for screenshots.
 * `diagnostic-fx.request` in the game directory holds `off`, `dry <throttle>` or `ab`; while set, the afterburner
 * presentation treats every aircraft as running at that setting. Inert in every normal game.
 *
 * `diagnostic-light.request` places effect lights for lighting tests: one per line,
 * `<dx> <dy> <dz> <radius> <level> <ticks> [boost]` relative to the player's position, each held (sustained) for
 * `<ticks>` client ticks; `off` removes them.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FxPreview {
    private const val REQUEST = "diagnostic-fx.request"

    /** Null: no override. Otherwise the engine setting to present. */
    @JvmStatic
    @Volatile
    var engine: Engine? = null
        private set

    class Engine(@JvmField val throttle: Double, @JvmField val afterburner: Boolean)

    private var poll = 0
    private class Probe(val key: Long, val x: Double, val y: Double, val z: Double, val radius: Double,
                        val level: Double, val boost: Double, var ticks: Int)
    private val probes = ArrayList<Probe>()

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        probes.removeIf { p ->
            com.atsuishio.superbwarfare.client.particle.FxLights.sustain(p.key, p.x, p.y, p.z, p.radius, p.level,
                p.boost, 1f, 0.55f, 0.25f)
            --p.ticks <= 0
        }
        if (++poll < 4) return
        poll = 0
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        readLights()
        val file = File(Minecraft.getInstance().gameDirectory, REQUEST)
        if (!file.isFile) return
        val parts = runCatching { file.readText().trim().split(Regex("\\s+")) }.getOrDefault(emptyList())
        file.delete()
        engine = when (parts.getOrNull(0)) {
            "dry" -> Engine(parts.getOrNull(1)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0, false)
            "ab" -> Engine(1.0, true)
            else -> null
        }
        Mod.LOGGER.info("FX preview: {}", parts.joinToString(" "))
    }

    private fun readLights() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val file = File(mc.gameDirectory, "diagnostic-light.request")
        if (!file.isFile) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        file.delete()
        for (line in lines) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.firstOrNull() == "off") { probes.clear(); continue }
            val v = p.mapNotNull { it.toDoubleOrNull() }
            if (v.size < 6) continue
            probes += Probe(0x4C0000000000L or (probes.size + 1L), player.x + v[0], player.y + v[1], player.z + v[2],
                v[3], v[4], v.getOrElse(6) { 1.0 }, v[5].toInt())
        }
        Mod.LOGGER.info("FX light probes: {}", probes.size)
    }
}
