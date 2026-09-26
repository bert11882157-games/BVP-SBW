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

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || ++poll < 4) return
        poll = 0
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
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
}
