package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.Mod
import com.google.gson.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraftforge.event.AddReloadListenerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/** Data-pack contract. Invalid definitions fail closed; equipment is never inferred from a model. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftArmamentRegistry {
    var aircraft: Map<ResourceLocation, JsonObject> = emptyMap(); private set
    var stores: Map<ResourceLocation, JsonObject> = emptyMap(); private set
    var revision: Long = 0; private set
    val categories = setOf("LASER_GUIDED", "GUN_POD", "BOMB", "AIR_TO_AIR", "ANTI_RADIATION", "CRUISE", "ROCKET_POD", "VISUAL_ONLY")

    /** Mass is per physical round or pod; a paired station contains two complete loads. */
    fun loadoutMassKg(definition: JsonObject, choices: Map<String, JsonObject>): Double =
        mounts(definition).sumOf { mount ->
            val store = choices[mount["Id"].asString] ?: return@sumOf 0.0
            (store["MassKg"]?.asDouble ?: 0.0) * mountCapacity(mount, store["Capacity"]?.asInt ?: 1)
        }

    internal fun installDiagnosticFixture(id: ResourceLocation, definition: JsonObject,
        fixtureStores: Map<ResourceLocation, JsonObject>): () -> Unit {
        check(java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios"))
        validate(definition, false); fixtureStores.values.forEach { validate(it, true) }
        val oldAircraft = aircraft; val oldStores = stores
        aircraft = aircraft + (id to definition); stores = stores + fixtureStores; revision++
        return { aircraft = oldAircraft; stores = oldStores; revision++ }
    }

    fun vector(value: JsonElement?): net.minecraft.world.phys.Vec3? = runCatching {
        val a = value!!.asJsonArray
        require(a.size() == 3)
        val v = net.minecraft.world.phys.Vec3(a[0].asDouble, a[1].asDouble, a[2].asDouble)
        require(v.x.isFinite() && v.y.isFinite() && v.z.isFinite())
        v
    }.getOrNull()

    /** Equipment keys are shared by paired wings and independent single stations. */
    fun mounts(definition: JsonObject): List<JsonObject> = listOf("Pairs", "Singles").flatMap { key ->
        definition.getAsJsonArray(key)?.map { it.asJsonObject } ?: emptyList()
    }

    fun mountPositions(mount: JsonObject): List<net.minecraft.world.phys.Vec3> {
        val single = mount.has("Position")
        require(!single || (!mount.has("Left") && !mount.has("Right")))
        return (if (single) listOf("Position") else listOf("Left", "Right")).map { key ->
            requireNotNull(vector(mount[key])).also { require(it.length() <= 128) }
        }
    }

    fun mountCapacity(mount: JsonObject, perPosition: Int): Int {
        require(perPosition in 1..10000)
        return perPosition * mountPositions(mount).size
    }

    fun launchPosition(mount: JsonObject, fired: Int): net.minecraft.world.phys.Vec3 {
        require(fired >= 0)
        val positions = mountPositions(mount)
        return positions[fired % positions.size]
    }

    fun validate(json: JsonObject, store: Boolean) {
        require(json.toString().length <= 12000 && json["Schema"]?.asInt == 1)
        require(json["Name"]?.asString?.length in 1..64)
        if (store) {
            require(json["Category"]?.asString in categories)
            for (key in listOf("Item", "AmmoItem", "Model", "Texture", "ProjectileProfile", "LaunchGunProfile", "GunProfile")) {
                json[key]?.let { require(it.asString.length <= 128 && ResourceLocation.tryParse(it.asString) != null) }
            }
            json["Guidance"]?.let {
                require(json["Category"]?.asString in setOf("AIR_TO_AIR", "ANTI_RADIATION"))
                val guidance = requireNotNull(AircraftMissileLauncher.guidance(json))
                require((json["Category"].asString == "ANTI_RADIATION") == (guidance.mode == "ANTI_RADIATION"))
            }
            json.getAsJsonObject("Flight")?.let { flight ->
                require(json.has("Guidance") || json["Category"]?.asString == "CRUISE")
                for ((key, limits) in mapOf("InitialSpeed" to (0.01..20.0), "MaxSpeed" to (0.1..30.0),
                    "AccelerationPerTick" to (0.001..2.0), "TurnDegreesPerSecond" to (0.1..360.0),
                    "Damage" to (1.0..5000.0), "BlastRadius" to (0.0..64.0))) {
                    val n = flight[key]?.asDouble ?: error("Missing missile $key")
                    require(n.isFinite() && n in limits)
                }
                require(flight["InitialSpeed"].asDouble <= flight["MaxSpeed"].asDouble)
                if (json["Category"]?.asString == "CRUISE") {
                    val range = flight["Range"]?.asDouble ?: error("Missing cruise Range")
                    require(range.isFinite() && range in 16.0..4096.0)
                }
            }
            json["MassKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.1..50000.0) }
            json.getAsJsonObject("Bomb")?.let { bomb ->
                require(json["Category"]?.asString == "BOMB")
                require(bomb["Mode"]?.asString in setOf("DUMB", "LASER", "GPS"))
                for ((key, range) in mapOf("Gravity" to (0.01..1.0), "DragMultiplier" to (0.0..20.0),
                    "TurnDegreesPerTick" to (0.0..15.0), "BlastRadius" to (1.0..64.0), "BlastDamage" to (1.0..5000.0))) {
                    val n = bomb[key]?.asDouble ?: error("Missing bomb $key")
                    require(n.isFinite() && n in range)
                }
            }
            json["Capacity"]?.let { require(it.asInt in 1..10000) }
            json["Scale"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.001..32.0) }
            json["LaunchOffset"]?.let { require(vector(it)?.length()?.let { length -> length <= 8.0 } == true) }
            return
        }
        fun names(key: String) {
            val a = json.getAsJsonArray(key) ?: JsonArray()
            require(a.size() <= 64 && a.all { it.asString.length in 1..128 })
        }
        names("BuiltInWeapons"); names("SuspendedWeapons")
        json["MaxPayloadKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.0..100000.0) }
        val pairs = json.getAsJsonArray("Pairs") ?: JsonArray()
        val singles = json.getAsJsonArray("Singles") ?: JsonArray()
        require(pairs.size() + singles.size() <= 16)
        require(json["MaxPayloadKg"]?.asDouble != 0.0 || pairs.size() + singles.size() == 0)
        require(pairs.none { it.asJsonObject.has("Position") })
        require(singles.all { it.asJsonObject.has("Position") })
        val ids = mutableSetOf<String>()
        for (pair in mounts(json)) {
            val id = pair["Id"].asString
            require(id.matches(Regex("[a-zA-Z0-9_.-]{1,48}")) && ids.add(id))
            require(pair["Name"]?.asString?.isNotBlank() == true && pair["Name"].asString.length <= 64)
            mountPositions(pair)
            val allowed = pair.getAsJsonArray("AllowedStores") ?: JsonArray()
            require(allowed.size() <= 32 && allowed.all { it.asString.length <= 128 && ResourceLocation.tryParse(it.asString) != null })
            pair["WeaponId"]?.let { require(it.asString.length in 1..128) }
            pair["WeaponChannel"]?.let { require(it.asString == pair["WeaponId"]?.asString) }
            pair.getAsJsonObject("NativeWeaponIds")?.let { mapping ->
                require(mapping.size() <= 32)
                for ((storeId, weapon) in mapping.entrySet()) {
                    val names = if (weapon.isJsonArray) weapon.asJsonArray.map { it.asString } else listOf(weapon.asString)
                    require(allowed.any { it.asString == storeId } && names.size in 1..32 && names.distinct().size == names.size)
                    for (name in names) {
                        require(name.length in 1..128)
                        require(json.getAsJsonArray("SuspendedWeapons")?.any { it.asString == name } == true)
                    }
                }
            }
        }
        json.getAsJsonObject("Radar")?.let { radar ->
            require(radar["Enabled"]?.isJsonPrimitive == true && radar["Enabled"].asJsonPrimitive.isBoolean)
            radar["Range"]?.let { val n = it.asDouble; require(n.isFinite() && n == kotlin.math.floor(n) && n in 16.0..4096.0) }
        }
        json.getAsJsonObject("Pod")?.let { pod ->
            require(vector(pod["Position"])?.length()?.let { it <= 128 } == true)
            require(pod["Source"]?.asString?.isNotBlank() == true)
            require(pod["YawLimit"].asDouble in 0.0..180.0)
            require(pod["PitchMin"].asDouble in -90.0..90.0)
            require(pod["PitchMax"].asDouble in pod["PitchMin"].asDouble..90.0)
            pod["MaxZoom"]?.let { require(it.asDouble.isFinite() && it.asDouble in 1.0..24.0) }
            pod["Range"]?.let { require(it.asDouble.isFinite() && it.asDouble in 1.0..8192.0) }
        }
    }

    private class Listener(private val isStore: Boolean) : SimpleJsonResourceReloadListener(
        Gson(), if (isStore) "sbw/aircraft_stores" else "sbw/aircraft_armaments") {
        override fun apply(input: Map<ResourceLocation, JsonElement>, resources: ResourceManager, profiler: ProfilerFiller) {
            val loaded = linkedMapOf<ResourceLocation, JsonObject>()
            for ((id, value) in input) try {
                val obj = value.asJsonObject
                validate(obj, isStore)
                if (!isStore) {
                    val used = mounts(obj).flatMap { mount ->
                        mount.getAsJsonArray("AllowedStores")?.map { it.asString } ?: emptyList()
                    }.toSet()
                    require(obj.toString().length + used.sumOf {
                        stores[ResourceLocation.tryParse(it)]?.toString()?.length ?: 0
                    } <= 40000) { "Aircraft catalogue exceeds snapshot budget" }
                }
                loaded[id] = obj.deepCopy()
            } catch (e: Exception) {
                Mod.LOGGER.error("Invalid aircraft {} {}: {}", if (isStore) "store" else "armament", id, e.message)
            }
            if (isStore) stores = loaded else aircraft = loaded
            revision++
        }
    }

    @SubscribeEvent
    fun reload(event: AddReloadListenerEvent) {
        event.addListener(Listener(true)); event.addListener(Listener(false))
    }
}
