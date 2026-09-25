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
    val categories = setOf("COMMAND_GUIDED", "LASER_GUIDED", "GUN_POD", "BOMB", "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE", "ROCKET_POD", "VISUAL_ONLY")

    /** Mass is per physical round or pod; a paired station contains two complete loads. */
    fun loadoutMassKg(definition: JsonObject, choices: Map<String, JsonObject>, counts: Map<String, Int> = emptyMap()): Double =
        mounts(definition).sumOf { mount ->
            val store = choices[mount["Id"].asString] ?: return@sumOf 0.0
            (store["MassKg"]?.asDouble ?: 0.0) * mountCapacity(mount, store["Capacity"]?.asInt ?: 1) *
                (counts[mount["Id"].asString] ?: 1) +
                (store["RackMassKg"]?.asDouble ?: 0.0) * mountPositions(mount).size
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

    /** A recessed ventral store may physically occupy the same volume as an internal bay. */
    fun validateMountConflicts(definition: JsonObject, selectedMounts: Set<String>) {
        for (mount in mounts(definition)) {
            val id = mount["Id"].asString
            if (id !in selectedMounts) continue
            for (other in mount.getAsJsonArray("ExclusiveWith") ?: JsonArray())
                require(other.asString !in selectedMounts) {
                    "${mount["Name"].asString} and ${other.asString} cannot be fitted together."
                }
        }
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

    private fun requireTntEquivalent(value: com.google.gson.JsonElement?) {
        if (value == null || value.isJsonNull) return
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "TntEquivalentKg must be a number" }
        val kg = value.asDouble
        require(kg.isFinite() && kg in 0.0..100000.0) { "TntEquivalentKg must be within 0..100000 kg" }
    }

    fun validate(json: JsonObject, store: Boolean) {
        require(json.toString().length <= 12000 && json["Schema"]?.asInt == 1)
        require(json["Name"]?.asString?.length in 1..64)
        if (store) {
            require(json["Category"]?.asString in categories)
            require((json["Category"]?.asString == "COMMAND_GUIDED") == json.has("CommandGuidance"))
            json.getAsJsonObject("CommandGuidance")?.let {
                require(it["Mode"]?.asString in setOf("MCLOS", "SACLOS"))
                require(json.has("LaunchGunProfile") && json.has("ProjectileProfile") &&
                    !json.has("Guidance") && !json.has("Flight") && !json.has("Bomb"))
            }
            for (key in listOf("Item", "AmmoItem", "Model", "Texture", "ProjectileProfile", "LaunchGunProfile", "GunProfile")) {
                json[key]?.let { require(it.asString.length <= 128 && ResourceLocation.tryParse(it.asString) != null) }
            }
            json["Guidance"]?.let {
                require(json["Category"]?.asString in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE", "BOMB"))
                val guidance = requireNotNull(AircraftMissileLauncher.guidance(json))
                it.asJsonObject["Presentation"]?.let { presentation ->
                    require(presentation.isJsonPrimitive && presentation.asJsonPrimitive.isString &&
                        presentation.asString in setOf("FIRE_AND_FORGET", "TV") && guidance.mode == "GROUND_INFRARED")
                }
                require((json["Category"].asString == "ANTI_RADIATION") == (guidance.mode == "ANTI_RADIATION"))
                require((json["Category"].asString in setOf("AIR_TO_GROUND", "BOMB")) == (guidance.mode == "GROUND_INFRARED"))
                require((json["Category"].asString == "CRUISE") == (guidance.mode == "ACTIVE_SURFACE_RADAR"))
                if (json["Category"].asString == "BOMB") require(json.getAsJsonObject("Bomb")?.get("Mode")?.asString == "TV")
            }
            json.getAsJsonObject("Flight")?.let { flight ->
                require(json["Category"]?.asString != "BOMB") { "Bombs cannot use powered missile flight" }
                require(json.has("Guidance") || json["Category"]?.asString == "CRUISE")
                for ((key, limits) in mapOf("InitialSpeed" to (0.01..20.0), "MaxSpeed" to (0.1..30.0),
                    "AccelerationPerTick" to (0.001..2.0), "TurnDegreesPerSecond" to (0.1..360.0),
                    "Damage" to (1.0..5000.0), "BlastRadius" to (0.0..64.0))) {
                    val n = flight[key]?.asDouble ?: error("Missing missile $key")
                    require(n.isFinite() && n in limits)
                }
                require(flight["InitialSpeed"].asDouble <= flight["MaxSpeed"].asDouble)
                flight["MaxLoadFactorG"]?.asDouble?.let { require(it.isFinite() && it in 0.1..50.0) }
                flight["BodyTurnLimitScale"]?.asDouble?.let {
                    require(flight.has("MaxLoadFactorG") && it.isFinite() && it in 1.0..3.0)
                }
                if (json["Category"]?.asString == "CRUISE") {
                    val range = flight["Range"]?.asDouble ?: error("Missing cruise Range")
                    require(range.isFinite() && range in 16.0..4096.0)
                    flight["Trajectory"]?.let {
                        require(it.asString == "BALLISTIC" && !json.has("Guidance"))
                        val loft = flight["LoftHeight"]?.asDouble ?: error("Missing ballistic LoftHeight")
                        require(loft.isFinite() && loft in 16.0..256.0)
                    }
                }
            }
            json["MassKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.1..50000.0) }
            // TNT-equivalent charge (kg) for the Hopkinson-Cranz blast model; absent/0 keeps the legacy blast.
            requireTntEquivalent(json["TntEquivalentKg"])
            json.getAsJsonObject("Flight")?.let { requireTntEquivalent(it["TntEquivalentKg"]) }
            json.getAsJsonObject("Bomb")?.let { bomb ->
                require(json["Category"]?.asString == "BOMB")
                requireTntEquivalent(bomb["TntEquivalentKg"])
                require(bomb["Mode"]?.asString in setOf("DUMB", "LASER", "GPS", "TV"))
                require((bomb["Mode"].asString == "TV") == json.has("Guidance"))
                if (bomb["Mode"].asString == "TV") require(json.getAsJsonObject("Guidance")["Presentation"]?.asString == "TV")
                bomb.getAsJsonObject("Penetrator")?.let { penetrator ->
                    require(penetrator.entrySet().map { it.key }.toSet() ==
                        setOf("MaxDepthBlocks", "MaxBlockHardness", "FuzeDelayTicks"))
                    require(penetrator["MaxDepthBlocks"].asBigDecimal.intValueExact() in 1..8)
                    require(penetrator["FuzeDelayTicks"].asBigDecimal.intValueExact() in 1..20)
                    val hardness = penetrator["MaxBlockHardness"].asDouble
                    require(hardness.isFinite() && hardness in 0.1..50.0)
                }
                val cluster = bomb.getAsJsonObject("Cluster")
                if (cluster != null) {
                    require(bomb["Mode"].asString == "DUMB")
                    require(bomb["BlastRadius"]?.asDouble == 0.0 && bomb["BlastDamage"]?.asDouble == 0.0)
                    val required = setOf("Count", "ReleaseHeight", "SpreadSpeed", "BombletDamage",
                        "BombletRadius", "LifetimeTicks")
                    val optional = setOf("Mode", "BombletProfile", "SensorRadius", "SensorShots",
                        "SensorProjectileProfile", AircraftClusterBomb.BOMBLET_TNT_JSON_KEY)
                    val keys = cluster.entrySet().map { it.key }.toSet()
                    require(keys.containsAll(required) && keys.all { it in required || it in optional })
                    val mode = cluster["Mode"]?.asString ?: "HE"
                    require(mode in setOf("HE", "HEAT", "SENSOR_FUZED"))
                    require((mode == "HEAT") == cluster.has("BombletProfile"))
                    require((mode == "SENSOR_FUZED") == cluster.has("SensorProjectileProfile"))
                    require((mode == "SENSOR_FUZED") ==
                        (cluster.has("SensorRadius") && cluster.has("SensorShots")))
                    for (key in listOf("BombletProfile", "SensorProjectileProfile"))
                        cluster[key]?.let { require(ResourceLocation.tryParse(it.asString) != null) }
                    if (mode == "SENSOR_FUZED") {
                        val sensorRadius = cluster["SensorRadius"].asDouble
                        require(sensorRadius.isFinite() && sensorRadius in 2.0..16.0)
                        require(cluster["SensorShots"].asBigDecimal.intValueExact() in 1..4)
                    }
                    if (mode != "HE") require(cluster["BombletDamage"].asDouble == 0.0)
                    requireTntEquivalent(cluster[AircraftClusterBomb.BOMBLET_TNT_JSON_KEY])
                    require(cluster["Count"].asBigDecimal.intValueExact() in 1..24)
                    require(cluster["LifetimeTicks"].asBigDecimal.intValueExact() in 20..200)
                    for ((key, range) in mapOf("ReleaseHeight" to (2.0..32.0), "SpreadSpeed" to (0.0..1.0),
                        "BombletDamage" to (0.0..2000.0), "BombletRadius" to (0.1..8.0))) {
                        val value = cluster[key].asDouble
                        require(value.isFinite() && value in range)
                    }
                }
                for ((key, range) in mapOf("Gravity" to (0.01..1.0), "DragMultiplier" to (0.0..20.0),
                    "TurnDegreesPerTick" to (0.0..15.0), "BlastRadius" to (if (cluster == null) 1.0..64.0 else 0.0..0.0),
                    "BlastDamage" to (if (cluster == null) 1.0..5000.0 else 0.0..0.0))) {
                    val n = bomb[key]?.asDouble ?: error("Missing bomb $key")
                    require(n.isFinite() && n in range)
                }
            }
            json["Capacity"]?.let { require(it.asInt in 1..10000) }
            json["MaxPerPylon"]?.let { require(it.asBigDecimal.intValueExact() in 1..AircraftPylonRacks.MAX_COPIES) }
            json["FixedRackCount"]?.let {
                val copies = it.asBigDecimal.intValueExact()
                require(json["Category"]?.asString in setOf("BOMB", "AIR_TO_GROUND", "AIR_TO_AIR") && json["Capacity"]?.asInt == 1)
                require(copies in 2..AircraftPylonRacks.MAX_COPIES &&
                    copies <= (json["MaxPerPylon"]?.asInt ?: 1))
            }
            json["RackSpacing"]?.let {
                val spacing = requireNotNull(vector(it))
                require(spacing.x in 0.1..4.0 && spacing.y in 0.0..4.0 && spacing.z in 0.0..8.0)
            }
            json["RackColumns"]?.let {
                require(json.has("FixedRackCount"))
                require(it.asBigDecimal.intValueExact() in 1..json["FixedRackCount"].asInt)
            }
            json["RackMassKg"]?.let {
                require(json.has("FixedRackCount") && it.asDouble.isFinite() && it.asDouble in 0.0..2000.0)
            }
            json["Scale"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.001..32.0) }
            AircraftStoreMountAnchor.validate(json)
            json[AircraftStoreModelForward.KEY]?.let {
                require(json.has("Model") && it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                    it.asString in AircraftStoreModelForward.values) { "ModelForward must be -Z or +Z for a modeled store" }
            }
            json["LaunchOffset"]?.let { require(vector(it)?.length()?.let { length -> length <= 8.0 } == true) }
            return
        }
        fun names(key: String) {
            val a = json.getAsJsonArray(key) ?: JsonArray()
            require(a.size() <= 64 && a.all { it.asString.length in 1..128 })
        }
        names("BuiltInWeapons"); names("SuspendedWeapons")
        json["MaxPayloadKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.0..100000.0) }
        json["MaxPylonMassKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.1..100000.0) }
        json["MaxWeaponsPerPylon"]?.let { require(it.asBigDecimal.intValueExact() in 1..AircraftPylonRacks.MAX_COPIES) }
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
            AircraftMountSweep.decode(pair, mountPositions(pair).size)
            pair["Internal"]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && pair.has("Position"))
                if (it.asBoolean) require(pair["MaxPylonMassKg"]?.asDouble?.isFinite() == true)
            }
            pair[AircraftMountGearInterlock.KEY]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) {
                    "${AircraftMountGearInterlock.KEY} must be true or false"
                }
            }
            pair["QuantitySelectable"]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
                require(!it.asBoolean || pair["Internal"]?.asBoolean == true)
            }
            pair["MaxWeaponsPerPylon"]?.let { require(it.asBigDecimal.intValueExact() in 1..AircraftPylonRacks.MAX_COPIES) }
            pair["MaxPylonMassKg"]?.let { require(it.asDouble.isFinite() && it.asDouble in 0.1..100000.0) }
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
        for (mount in mounts(json)) mount.getAsJsonArray("ExclusiveWith")?.let { excluded ->
            require(excluded.size() <= 15 && excluded.map { it.asString }.distinct().size == excluded.size())
            require(excluded.all { it.asString in ids && it.asString != mount["Id"].asString })
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
