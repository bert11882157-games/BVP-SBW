package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * Vehicle sound mix (owner 2026-09-29): five categories the player sets in Options > Music & Sounds > Vehicle
 * Sounds, on top of Minecraft's own category sliders, plus the balance trims the mod ships with.
 *
 * Every vehicle sound is classified once per volume calculation ([factor], called from SoundEngineMixin for the
 * first volume of a sound and for every later update): instances that know their vehicle implement [Tagged]; plain
 * sounds are classified by their sound id ([byId]). Anything unclassified plays unchanged.
 *
 * Balance (measured 2026-09-29, EBU R128 loudness of the shipped files times their authored gain): ground engine
 * loops ran at about -11 LUFS continuously and tracks at about -10 at full speed, as loud as the peaks of vehicle
 * gunfire (median -11), so machine guns and engines masked each other; aircraft engines ran about 4 dB under
 * aircraft guns. The trims below put ground engines and tracks about 7-8 dB and aircraft engines about 3 dB lower.
 */
object VehicleAudioMix {
    enum class Category(val key: String, val label: String, val trim: Float) {
        GROUND_ENGINES("ground_engines", "Ground vehicle engines", 0.42f),
        GROUND_WEAPONS("ground_weapons", "Ground vehicle weapons", 1.0f),
        AIRCRAFT_ENGINES("aircraft_engines", "Aircraft engines", 0.7f),
        AIRCRAFT_WEAPONS("aircraft_weapons", "Aircraft weapons", 1.0f),
        AIRCRAFT_EFFECTS("aircraft_effects", "Aircraft effects (sonic boom, flares...)", 1.0f),
    }

    /** A sound that knows which part of the mix it belongs to (null: not a vehicle sound). */
    interface Tagged {
        fun vehicleMixCategory(): Category?
    }

    const val MAX_LEVEL = 2.0f
    private const val FILE = "superbwarfare-vehicle-sounds.json"
    private val levels = FloatArray(Category.entries.size) { 1f }
    private var loaded = false

    /** The player's slider for [category], 0..[MAX_LEVEL] (1 = the shipped balance). */
    @JvmStatic
    fun level(category: Category): Float {
        load()
        return levels[category.ordinal]
    }

    @JvmStatic
    fun setLevel(category: Category, value: Float) {
        load()
        levels[category.ordinal] = if (value.isFinite()) value.coerceIn(0f, MAX_LEVEL) else 1f
    }

    /** Effective multiplier: shipped trim times the player's slider. */
    @JvmStatic
    fun gain(category: Category): Float = category.trim * level(category)

    /** Volume multiplier for [instance] (1 when it is not a vehicle sound). */
    @JvmStatic
    fun factor(instance: SoundInstance?): Float {
        if (instance == null) return 1f
        val category = (instance as? Tagged)?.vehicleMixCategory()
            ?: runCatching { byId(instance.location) }.getOrNull()
            ?: return 1f
        return gain(category)
    }

    @JvmStatic
    fun isAircraft(vehicle: VehicleEntity?): Boolean =
        vehicle != null && (vehicle.vehicleType == VehicleType.AIRPLANE || vehicle.vehicleType == VehicleType.HELICOPTER)

    @JvmStatic
    fun engineOf(vehicle: VehicleEntity?): Category? =
        vehicle?.let { if (isAircraft(it)) Category.AIRCRAFT_ENGINES else Category.GROUND_ENGINES }

    @JvmStatic
    fun weaponsOf(vehicle: VehicleEntity?): Category? =
        vehicle?.let { if (isAircraft(it)) Category.AIRCRAFT_WEAPONS else Category.GROUND_WEAPONS }

    private val AIRCRAFT_EFFECT_IDS = setOf(
        "superbwarfare:sonic_boom", "superbwarfare:aircraft_flare_release", "superbwarfare:aircraft_tire_touchdown",
        "superbwarfare:aam_lock", "superbwarfare:arm_lock", "superbwarfare:heli_crash",
    )

    /** Classification of sounds that do not carry their vehicle, by id. */
    @JvmStatic
    fun byId(id: ResourceLocation?): Category? {
        if (id == null) return null
        val ns = id.namespace
        val path = id.path
        return when {
            "$ns:$path" in AIRCRAFT_EFFECT_IDS -> Category.AIRCRAFT_EFFECTS
            ns == "supersonic" && path.startsWith("sonic_boom") -> Category.AIRCRAFT_EFFECTS
            ns == "bvp_audio" && path.startsWith("air/") -> Category.AIRCRAFT_ENGINES
            ns == "bvp_audio" && (path.startsWith("engine/") || path.startsWith("tracks/") ||
                path.startsWith("turret/")) -> Category.GROUND_ENGINES
            ns == "superbwarfare" && path == "distant_jet_engine" -> Category.AIRCRAFT_ENGINES
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------- persistence

    private fun file(): Path? = runCatching { FMLPaths.CONFIGDIR.get().resolve(FILE) }.getOrNull()

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val path = file() ?: return
        if (!Files.isRegularFile(path)) return
        runCatching {
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            for (c in Category.entries) {
                val v = json.get(c.key)
                if (v != null && v.isJsonPrimitive) setLevel(c, v.asFloat)
            }
        }.onFailure { com.atsuishio.superbwarfare.Mod.LOGGER.warn("Vehicle sound mix: could not read {}: {}", path, it.toString()) }
    }

    @JvmStatic
    @Synchronized
    fun save() {
        val path = file() ?: return
        runCatching {
            val json = JsonObject()
            for (c in Category.entries) json.addProperty(c.key, Math.round(levels[c.ordinal] * 100f) / 100f)
            Files.createDirectories(path.parent)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(json))
        }.onFailure { com.atsuishio.superbwarfare.Mod.LOGGER.warn("Vehicle sound mix: could not write {}: {}", path, it.toString()) }
    }

    /** Test hook: forget loaded values. */
    @JvmStatic
    internal fun resetForTest() {
        levels.fill(1f)
        loaded = true
    }
}
