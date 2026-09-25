package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.Mod
import com.google.gson.Gson
import com.google.gson.JsonElement
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.Entity
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.event.AddReloadListenerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries

/**
 * Server data table of TNT equivalents for munitions spawned without GunData (see [TntDefaultTable]).
 * Loaded from every `data/<namespace>/blast/<name>.json`; files merge in id order, later ids win.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
object TntDefaults : SimpleJsonResourceReloadListener(Gson(), "blast") {
    @Volatile
    private var entries: Map<String, Double> = emptyMap()

    override fun apply(objects: Map<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val merged = LinkedHashMap<String, Double>()
        for ((id, json) in objects.entries.sortedBy { it.key.toString() }) {
            try {
                merged.putAll(TntDefaultTable.parse(json))
            } catch (failure: RuntimeException) {
                Mod.LOGGER.error("Ignoring invalid TNT-equivalent table {}: {}", id, failure.message)
            }
        }
        entries = merged
        Mod.LOGGER.debug("Loaded {} TNT-equivalent defaults", merged.size)
    }

    /** TNT equivalent (kg) for a table key, or null when the table has none. */
    @JvmStatic
    fun get(key: String): Double? = entries[key]

    /** Table default for an entity keyed by its registered type id, e.g. `superbwarfare:mortar_shell`. */
    @JvmStatic
    fun forEntity(entity: Entity): Double? {
        if (entries.isEmpty()) return null
        val id = ForgeRegistries.ENTITY_TYPES.getKey(entity.type) ?: return null
        return entries[id.toString()]
    }

    /** A config override (>= 0) wins over the table entry [key]; -1 uses the table. */
    @JvmStatic
    fun configured(value: ForgeConfigSpec.DoubleValue, key: String): Double {
        val configValue = runCatching { value.get() }.getOrDefault(-1.0)
        return TntDefaultTable.configuredOrTable(configValue, get(key))
    }

    @SubscribeEvent
    fun onAddReloadListeners(event: AddReloadListenerEvent) {
        event.addListener(this)
    }
}
