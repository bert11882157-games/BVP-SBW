package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.io.File

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): external test orchestration asks the client for a burst
 * of in-game screenshots by writing `diagnostic-screenshot.request` into the game directory, containing
 * `<prefix> <delayTicks> <count> <intervalTicks>`. Captures go to the normal `screenshots/` folder together with a
 * log line carrying the live particle count. Inert in every normal game.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object DiagnosticScreenshots {
    private const val REQUEST = "diagnostic-screenshot.request"
    private const val POLL_TICKS = 4

    private class Burst(val prefix: String, var delay: Int, var remaining: Int, val interval: Int) {
        var index = 0
    }

    private var burst: Burst? = null
    private var poll = 0

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        val mc = Minecraft.getInstance()
        val active = burst
        if (active != null) {
            step(mc, active)
            return
        }
        if (++poll < POLL_TICKS) return
        poll = 0
        val file = File(mc.gameDirectory, REQUEST)
        if (!file.isFile) return
        val parts = runCatching { file.readText().trim().split(Regex("\\s+")) }.getOrDefault(emptyList())
        file.delete()
        val prefix = parts.getOrNull(0)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: return
        burst = Burst(prefix,
            parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 200) ?: 0,
            parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 40) ?: 1,
            parts.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 40) ?: 2)
        Mod.LOGGER.info("Diagnostic screenshot burst requested: {}", parts.joinToString(" "))
    }

    private fun step(mc: Minecraft, active: Burst) {
        if (active.delay > 0) {
            active.delay--
            return
        }
        val name = "${active.prefix}-${active.index.toString().padStart(2, '0')}.png"
        Screenshot.grab(mc.gameDirectory, name, mc.mainRenderTarget) { }
        Mod.LOGGER.info("Diagnostic screenshot {} particles={}", name, mc.particleEngine.countParticles())
        active.index++
        active.remaining--
        if (active.remaining <= 0) burst = null else active.delay = active.interval - 1
    }
}
