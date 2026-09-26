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
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): `diagnostic-controls.request` in the game directory holds
 * `<elevator> <aileron> <rudder>` (-1..1) that every aircraft's presented control surfaces and stick show instead of the
 * accepted input, so tests can photograph deflections without a pilot. Remove the file to stop. Visual only.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object DiagnosticControlOverride {
    private const val REQUEST = "diagnostic-controls.request"
    private const val POLL_TICKS = 10
    private var poll = 0
    private var values: DoubleArray? = null

    /** elevator, aileron, rudder, or null when no override is active. */
    @JvmStatic
    fun current(): DoubleArray? = values

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || ++poll < POLL_TICKS) return
        poll = 0
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) {
            values = null
            return
        }
        val file = File(Minecraft.getInstance().gameDirectory, REQUEST)
        val next = if (!file.isFile) null else runCatching {
            val parts = file.readText().trim().split(Regex("\\s+")).map { it.toDouble().coerceIn(-1.0, 1.0) }
            DoubleArray(3) { parts.getOrElse(it) { 0.0 } }
        }.getOrNull()
        if ((next == null) != (values == null) || (next != null && !next.contentEquals(values)))
            Mod.LOGGER.info("Diagnostic control override: {}", next?.joinToString(" ") ?: "off")
        values = next
    }
}
