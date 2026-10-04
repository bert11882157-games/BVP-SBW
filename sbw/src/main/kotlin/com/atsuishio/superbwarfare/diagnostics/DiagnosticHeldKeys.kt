package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.io.File

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): `diagnostic-keys.request` in the game directory lists key
 * mapping names (for example `key.superbwarfare.release_decoy`) that held-key polling treats as physically held, so
 * scripted tests can drive vehicle actions through the normal client input path without focusing the window.
 * Remove the file (or leave it empty) to release them.
 */
@EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object DiagnosticHeldKeys {
    private const val REQUEST = "diagnostic-keys.request"
    private const val POLL_TICKS = 2
    private var poll = 0
    private var held: Set<String> = emptySet()

    @JvmStatic
    fun isHeld(mapping: KeyMapping): Boolean = held.isNotEmpty() && mapping.name in held

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START || ++poll < POLL_TICKS) return
        poll = 0
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) {
            held = emptySet()
            return
        }
        val file = File(Minecraft.getInstance().gameDirectory, REQUEST)
        val next = if (!file.isFile) emptySet() else runCatching {
            file.readText().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())
        if (next != held) Mod.LOGGER.info("Diagnostic held keys: {}", if (next.isEmpty()) "none" else next.joinToString(" "))
        held = next
    }
}
