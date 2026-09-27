package com.atsuishio.superbwarfare.client.sound.vehicle

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * Authored vehicle audio (engine start / idle / drive / stop, tracks, turret slewing), one JSON per vehicle type at
 * `assets/<entity namespace>/sbw/vehicle_audio/<entity path>.json`, or shared ones any profile can `extends`
 * (`"extends": "<namespace>:<path>"` -> `assets/<namespace>/sbw/vehicle_audio/<path>.json`). Local fields override
 * the parent field by field. A vehicle with a profile is voiced by [VehicleAudioController] instead of its native or
 * custom engine loop and the per-tick turret one-shot.
 *
 * ```
 * { "extends": "bvp_audio:shared/t90",
 *   "engine": { "start": "...", "idle": "...", "drive": "...", "stop": "...", "startSeconds": 1.6,
 *               "range": 96, "volume": 1.0, "interiorVolume": 0.8,
 *               "idlePitch": [0.95, 1.1], "drivePitch": [0.85, 1.3], "driveVolume": [0.0, 1.0] },
 *   "tracks": { "loop": "...", "range": 48, "volume": 0.8, "fullSpeed": 0.5 },
 *   "turret": { "loop": "...", "start": "...", "stop": "...", "range": 32, "volume": 0.7, "fullRate": 20.0 } }
 * ```
 */
@Mod.EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID, value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.MOD)
object VehicleAudioProfiles : SimplePreparableReloadListener<Map<ResourceLocation, JsonObject>>() {
    // the mod logger: class loggers of SBW objects do not reach latest.log in the pack's log configuration
    private val LOGGER get() = com.atsuishio.superbwarfare.Mod.LOGGER
    private const val FOLDER = "sbw/vehicle_audio"

    data class Engine(
        val start: ResourceLocation?, val idle: ResourceLocation?, val drive: ResourceLocation?,
        val stop: ResourceLocation?, val startSeconds: Float, val range: Float, val volume: Float,
        val interiorVolume: Float, val idlePitch: FloatArray, val drivePitch: FloatArray, val driveVolume: FloatArray,
    )

    data class Tracks(val loop: ResourceLocation, val range: Float, val volume: Float, val fullSpeed: Float)

    data class Turret(
        val loop: ResourceLocation, val start: ResourceLocation?, val stop: ResourceLocation?,
        val range: Float, val volume: Float, val fullRate: Float,
    )

    data class Profile(val id: ResourceLocation, val engine: Engine?, val tracks: Tracks?, val turret: Turret?)

    @Volatile private var profiles: Map<ResourceLocation, Profile> = emptyMap()

    /** Profile for an entity type id, or null (the vehicle keeps its native audio). */
    @JvmStatic
    fun forType(type: ResourceLocation?): Profile? = if (type == null) null else profiles[type]

    @JvmStatic
    fun count() = profiles.size

    override fun prepare(manager: ResourceManager, profiler: ProfilerFiller): Map<ResourceLocation, JsonObject> {
        val out = HashMap<ResourceLocation, JsonObject>()
        for ((file, resource) in manager.listResources(FOLDER) { it.path.endsWith(".json") }) {
            val path = file.path.substring(FOLDER.length + 1, file.path.length - ".json".length)
            try {
                resource.openAsReader().use { reader ->
                    val json = JsonParser.parseReader(reader)
                    if (json.isJsonObject) out[ResourceLocation(file.namespace, path)] = json.asJsonObject
                }
            } catch (e: Exception) {
                LOGGER.warn("Vehicle audio profile {} is unreadable: {}", file, e.toString())
            }
        }
        return out
    }

    override fun apply(raw: Map<ResourceLocation, JsonObject>, manager: ResourceManager, profiler: ProfilerFiller) {
        val resolved = HashMap<ResourceLocation, Profile>()
        for (id in raw.keys) {
            // shared profiles (a "shared/" path) are only parents, never a vehicle type
            if (id.path.startsWith("shared/")) continue
            val merged = flatten(id, raw, 0) ?: continue
            try {
                resolved[id] = Profile(id, engine(merged.getAsJsonObject("engine")),
                    tracks(merged.getAsJsonObject("tracks")), turret(merged.getAsJsonObject("turret")))
            } catch (e: Exception) {
                LOGGER.warn("Vehicle audio profile {} is invalid: {}", id, e.toString())
            }
        }
        profiles = resolved
        VehicleAudioController.clear()
        LOGGER.info("Loaded {} authored vehicle audio profiles", resolved.size)
    }

    private fun flatten(id: ResourceLocation, raw: Map<ResourceLocation, JsonObject>, depth: Int): JsonObject? {
        val own = raw[id] ?: return null
        val parentId = own.get("extends")?.asString?.let(ResourceLocation::tryParse)
        if (parentId == null || depth > 8) return own
        val parent = flatten(parentId, raw, depth + 1) ?: return own
        val out = parent.deepCopy()
        for ((key, value) in own.entrySet()) {
            if (key == "extends") continue
            val base = out.get(key)
            if (value.isJsonObject && base != null && base.isJsonObject) {
                val section = base.asJsonObject.deepCopy()
                for ((k, v) in value.asJsonObject.entrySet()) section.add(k, v)
                out.add(key, section)
            } else out.add(key, value)
        }
        return out
    }

    private fun JsonObject.sound(key: String): ResourceLocation? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse)

    private fun JsonObject.float(key: String, fallback: Float): Float =
        get(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: fallback

    private fun JsonObject.pair(key: String, a: Float, b: Float): FloatArray {
        val e: JsonElement = get(key) ?: return floatArrayOf(a, b)
        if (!e.isJsonArray || e.asJsonArray.size() != 2) return floatArrayOf(a, b)
        return floatArrayOf(e.asJsonArray[0].asFloat, e.asJsonArray[1].asFloat)
    }

    private fun engine(o: JsonObject?): Engine? {
        o ?: return null
        val idle = o.sound("idle")
        val drive = o.sound("drive")
        if (idle == null && drive == null) return null
        return Engine(o.sound("start"), idle, drive, o.sound("stop"), o.float("startSeconds", 1.5f).coerceIn(0f, 30f),
            o.float("range", 96f).coerceIn(8f, 1024f), o.float("volume", 1f).coerceIn(0f, 2f),
            o.float("interiorVolume", 0.8f).coerceIn(0f, 2f), o.pair("idlePitch", 1f, 1.1f),
            o.pair("drivePitch", 0.9f, 1.3f), o.pair("driveVolume", 0.15f, 1f))
    }

    private fun tracks(o: JsonObject?): Tracks? {
        o ?: return null
        val loop = o.sound("loop") ?: return null
        return Tracks(loop, o.float("range", 48f).coerceIn(4f, 512f), o.float("volume", 0.8f).coerceIn(0f, 2f),
            o.float("fullSpeed", 0.5f).coerceIn(0.05f, 5f))
    }

    private fun turret(o: JsonObject?): Turret? {
        o ?: return null
        val loop = o.sound("loop") ?: return null
        return Turret(loop, o.sound("start"), o.sound("stop"), o.float("range", 32f).coerceIn(4f, 256f),
            o.float("volume", 0.7f).coerceIn(0f, 2f), o.float("fullRate", 20f).coerceIn(0.5f, 180f))
    }

    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(this)
    }
}
