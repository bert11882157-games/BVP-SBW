package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.UUID

/** Parsed once per receipt. Rendering never parses mutable JSON or changes a loadout. */
data class AircraftStoreView(
    val id: String, val name: String, val category: String,
    val item: ResourceLocation?, val model: ResourceLocation?, val texture: ResourceLocation?,
    val scale: Double, val capacity: Int?, val guidedAirToAir: Boolean = false,
) {
    val visualOnly: Boolean get() = (category == "AIR_TO_AIR" && !guidedAirToAir) || category == "VISUAL_ONLY"
    val categoryLabel: String get() = when (category) {
        "AIR_TO_AIR" -> if (guidedAirToAir) "Air-to-air guided missile" else "Air-to-air · visual only"
        "VISUAL_ONLY" -> "Visual only"
        "LASER_GUIDED" -> "Laser guided"
        "GUN_POD" -> "Gun pod"
        "ROCKET_POD" -> "Rocket pod"
        else -> "Bomb"
    }
}

interface AircraftMountView {
    val id: String
    val name: String
    val positions: List<Vec3>
    val allowed: List<String>
    val groups: Map<String, List<String>>
}

data class AircraftPairView(
    override val id: String, override val name: String, val left: Vec3, val right: Vec3,
    override val allowed: List<String>, override val groups: Map<String, List<String>>,
) : AircraftMountView {
    override val positions: List<Vec3> = Collections.unmodifiableList(listOf(left, right))
}

data class AircraftSingleView(
    override val id: String, override val name: String, val position: Vec3,
    override val allowed: List<String>, override val groups: Map<String, List<String>>,
) : AircraftMountView {
    override val positions: List<Vec3> = Collections.singletonList(position)
}

data class AircraftPodView(val position: Vec3, val yawLimit: Float, val pitchMin: Float,
                           val pitchMax: Float, val source: String,
                           val maxZoom: Double = 24.0, val range: Double = 8192.0)

data class AircraftSeekView(val revision: Long, val weaponId: String, val target: UUID?,
                            val position: Vec3?, val progress: Double, val ready: Boolean,
                            val status: String, val category: String = "", val guidanceMode: String = "",
                            val coneDegrees: Double = 0.0, val range: Double = 0.0,
                            val lockTicks: Int = 0, val slot: String = "") {
    val activeAam: Boolean get() = category == "AIR_TO_AIR" && weaponId.isNotBlank() &&
        guidanceMode in setOf("INFRARED", "ACTIVE_RADAR", "SEMI_ACTIVE_RADAR") &&
        coneDegrees in 1.0..60.0 && range in 16.0..4096.0 && status != "UNAVAILABLE"
}

data class AircraftDefinitionView @JvmOverloads constructor(
    val name: String, val builtIn: List<String>, val pairs: List<AircraftPairView>,
    val pod: AircraftPodView?, val neutralGroups: Map<String, List<String>>,
    val singles: List<AircraftSingleView> = emptyList(),
) {
    val mounts: List<AircraftMountView> = Collections.unmodifiableList(pairs + singles)
}

data class AircraftArmamentSnapshot(
    val vehicle: UUID, val entityId: Int, val revision: Long,
    val definition: AircraftDefinitionView, val stores: Map<String, AircraftStoreView>,
    val selections: Map<String, String>, val presets: Map<String, Map<String, String>>,
    val point: Vec3?, val podActive: Boolean, val message: String,
    val fired: Map<String, Int> = emptyMap(), val seekPair: String = "", val seekStatus: Int = 0,
    val seek: AircraftSeekView? = null,
) {
    /** Launches alternate across the mount's physical positions. Pods stay after firing. */
    fun storePresent(mount: AircraftMountView, position: Int): Boolean {
        val store = stores[selections[mount.id]] ?: return false
        if (store.category != "LASER_GUIDED" && store.category != "BOMB" && !store.guidedAirToAir) return true
        val capacity = store.capacity ?: 1
        return (fired[mount.id] ?: 0) < (capacity - 1) * mount.positions.size + position + 1
    }
    /** Global inventory never activates a store on a different pair. */
    fun visibleBones(): Set<String> = Collections.unmodifiableSet(buildSet {
        for (mount in definition.mounts) mount.groups[selections[mount.id]]?.let(::addAll)
    })

    fun allStoreBones(): Set<String> = Collections.unmodifiableSet(buildSet {
        definition.neutralGroups.values.forEach(::addAll)
        definition.mounts.forEach { mount -> mount.groups.values.forEach(::addAll) }
    })

    companion object {
        private val categories = setOf("LASER_GUIDED", "GUN_POD", "BOMB", "AIR_TO_AIR", "ROCKET_POD", "VISUAL_ONLY")

        fun decode(json: JsonObject): AircraftArmamentSnapshot? = try {
            val vehicle = UUID.fromString(string(json, "Vehicle", 36))
            val entityId = integer(json, "EntityId", 0, Int.MAX_VALUE)
            val rawRevision = json.get("Revision") ?: error("revision")
            require(rawRevision.isJsonPrimitive && rawRevision.asJsonPrimitive.isNumber)
            val revision = rawRevision.asBigDecimal.longValueExact().also { require(it >= 0) }
            val raw = json.getAsJsonObject("Definition") ?: error("definition")
            require(integer(raw, "Schema", 1, 1) == 1)
            val pairs = array(raw, "Pairs", 32).map { element ->
                val pair = element.asJsonObject
                require(!pair.has("Position"))
                val allowed = strings(pair, "AllowedStores", 256).also { it.forEach(::resource) }
                val groups = groups(pair, "StoreGroups")
                require(groups.keys.all { it in allowed })
                AircraftPairView(string(pair, "Id", 64), string(pair, "Name", 96),
                    vector(pair.get("Left"), 4096.0), vector(pair.get("Right"), 4096.0),
                    allowed, groups)
            }.also { require(it.map(AircraftPairView::id).distinct().size == it.size) }
            val singles = array(raw, "Singles", 16).map { element ->
                val mount = element.asJsonObject
                require(!mount.has("Left") && !mount.has("Right"))
                val allowed = strings(mount, "AllowedStores", 256).also { it.forEach(::resource) }
                val groups = groups(mount, "StoreGroups")
                require(groups.keys.all { it in allowed })
                AircraftSingleView(string(mount, "Id", 64), string(mount, "Name", 96),
                    vector(mount.get("Position"), 128.0), allowed, groups)
            }
            require(pairs.size + singles.size <= 16)
            require((pairs.map { it.id } + singles.map { it.id }).distinct().size == pairs.size + singles.size)
            val pod = raw.get("Pod")?.takeUnless(JsonElement::isJsonNull)?.asJsonObject?.let {
                val yaw = number(it, "YawLimit", 170.0, 1.0, 180.0).toFloat()
                val min = number(it, "PitchMin", -90.0, -90.0, 90.0).toFloat()
                val max = number(it, "PitchMax", 20.0, -90.0, 90.0).toFloat()
                require(min < max)
                AircraftPodView(vector(it.get("Position"), 4096.0), yaw, min, max,
                    string(it, "Source", 512), number(it, "MaxZoom", 24.0, 1.0, 64.0),
                    number(it, "Range", 8192.0, 1.0, 8192.0))
            }
            val definition = AircraftDefinitionView(string(raw, "Name", 96),
                strings(raw, "BuiltInWeapons", 32), Collections.unmodifiableList(pairs), pod,
                groups(raw, "StoreGroups"), Collections.unmodifiableList(singles))
            val rawStores = json.getAsJsonObject("Stores") ?: error("stores")
            require(rawStores.size() <= 256)
            val stores = rawStores.entrySet().associate { (id, value) ->
                resource(id)
                val store = value.asJsonObject
                require(integer(store, "Schema", 1, 1) == 1)
                val category = string(store, "Category", 32).also { require(it in categories) }
                id to AircraftStoreView(id, string(store, "Name", 96), category,
                    optionalResource(store, "Item"), optionalResource(store, "Model"),
                    optionalResource(store, "Texture"), number(store, "Scale", 1.0, 0.001, 64.0),
                    store.get("Capacity")?.let { integer(store, "Capacity", 1, 100000) },
                    category == "AIR_TO_AIR" && store.has("Guidance"))
            }
            val selections = selections(json.getAsJsonObject("Selections"), definition)
            val rawFired = json.getAsJsonObject("Fired") ?: JsonObject()
            require(rawFired.size() <= definition.mounts.size)
            val fired = rawFired.entrySet().associate { (key, _) ->
                require(definition.mounts.any { it.id == key })
                key to integer(rawFired, key, 0, 20000)
            }
            val rawPresets = json.getAsJsonObject("Presets") ?: JsonObject()
            require(rawPresets.size() <= 64)
            val presets = rawPresets.entrySet().associate { (name, value) ->
                require(name.isNotBlank() && name.length <= 32)
                name to selections(value.asJsonObject, definition)
            }
            AircraftArmamentSnapshot(vehicle, entityId, revision, definition,
                Collections.unmodifiableMap(stores), selections, Collections.unmodifiableMap(presets),
                json.get("Point")?.takeUnless(JsonElement::isJsonNull)?.let { vector(it, 30_000_000.0) },
                boolean(json, "PodActive", false) && pod != null,
                json.get("Message")?.let { string(json, "Message", 512, true) } ?: "",
                Collections.unmodifiableMap(fired),
                json.get("SeekPair")?.let { string(json, "SeekPair", 48, true) } ?: "",
                json.get("SeekStatus")?.let { integer(json, "SeekStatus", -1, 2) } ?: 0,
                json.getAsJsonObject("Seek")?.let(::decodeSeek))
        } catch (_: RuntimeException) { null }

        fun decodeSeek(json: JsonObject): AircraftSeekView {
            val rawRevision = json.get("Revision") ?: error("seek revision")
            require(rawRevision.isJsonPrimitive && rawRevision.asJsonPrimitive.isNumber)
            val revision = rawRevision.asBigDecimal.longValueExact().also { require(it >= 0) }
            val status = string(json, "Status", 32).also {
                require(it in setOf("NO_TARGET", "ACQUIRING", "READY", "UNAVAILABLE"))
            }
            val target = json.get("TargetUUID")?.takeUnless(JsonElement::isJsonNull)?.let {
                UUID.fromString(string(json, "TargetUUID", 36))
            }
            val position = json.get("TargetPosition")?.takeUnless(JsonElement::isJsonNull)?.let {
                vector(it, 30_000_000.0)
            }
            val ready = boolean(json, "Ready", false)
            val progress = number(json, "Progress", 0.0, 0.0, 1.0)
            val weapon = string(json, "WeaponId", 128, true)
            require((target == null) == (position == null))
            require(target == null || weapon.isNotBlank())
            require((status == "READY") == ready)
            require(!ready || (target != null && status == "READY" && progress == 1.0))
            fun optionalString(key: String) = if (json.has(key)) string(json, key, 32, true) else ""
            val category = optionalString("Category")
            val mode = optionalString("GuidanceMode")
            val slot = optionalString("Slot")
            require(category.isEmpty() || category in categories)
            require(mode in setOf("", "INFRARED", "ACTIVE_RADAR", "SEMI_ACTIVE_RADAR"))
            require(slot in setOf("", "PRIMARY", "SECONDARY"))
            return AircraftSeekView(revision, weapon, target,
                position, progress, ready, status, category, mode,
                number(json, "ConeDegrees", 0.0, 0.0, 60.0), number(json, "Range", 0.0, 0.0, 4096.0),
                if (json.has("LockTicks")) integer(json, "LockTicks", 0, 200) else 0, slot)
        }

        fun selections(raw: JsonObject?, definition: AircraftDefinitionView): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            if (raw == null) return Collections.unmodifiableMap(result)
            require(raw.size() <= definition.mounts.size)
            for ((pairId, value) in raw.entrySet()) {
                val pair = definition.mounts.firstOrNull { it.id == pairId } ?: error("unknown hardpoint")
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                val id = value.asString
                require(id in pair.allowed)
                result[pairId] = id
            }
            return Collections.unmodifiableMap(result)
        }

        fun vector(value: JsonElement?, limit: Double): Vec3 {
            val array = value?.asJsonArray ?: error("vector")
            require(array.size() == 3)
            val x = finiteNumber(array[0], -limit, limit)
            val y = finiteNumber(array[1], -limit, limit)
            val z = finiteNumber(array[2], -limit, limit)
            return Vec3(x, y, z)
        }

        private fun groups(json: JsonObject, key: String): Map<String, List<String>> {
            val raw = json.getAsJsonObject(key) ?: return emptyMap()
            require(raw.size() <= 256)
            var count = 0
            return Collections.unmodifiableMap(raw.entrySet().associate { (id, value) ->
                resource(id)
                val names = strings(value.asJsonArray, 128)
                count += names.size
                require(count <= 1024)
                id to names
            })
        }

        private fun strings(json: JsonObject, key: String, cap: Int): List<String> =
            strings(array(json, key, cap), cap)

        private fun strings(array: JsonArray, cap: Int): List<String> {
            require(array.size() <= cap)
            val result = array.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { text -> require(text.isNotBlank() && text.length <= 128) }
            }
            require(result.distinct().size == result.size)
            return Collections.unmodifiableList(result)
        }

        private fun array(json: JsonObject, key: String, cap: Int): JsonArray =
            (json.getAsJsonArray(key) ?: JsonArray()).also { require(it.size() <= cap) }

        private fun resource(text: String): ResourceLocation =
            ResourceLocation.tryParse(text)?.takeIf { text.length <= 256 } ?: error("resource ID")

        private fun optionalResource(json: JsonObject, key: String): ResourceLocation? =
            json.get(key)?.takeUnless(JsonElement::isJsonNull)?.let { resource(string(json, key, 256)) }

        private fun string(json: JsonObject, key: String, cap: Int, empty: Boolean = false): String {
            val value = json.get(key) ?: error(key)
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.also { require(it.length <= cap && (empty || it.isNotBlank())) }
        }

        private fun boolean(json: JsonObject, key: String, fallback: Boolean): Boolean {
            val value = json.get(key) ?: return fallback
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            return value.asBoolean
        }

        private fun integer(json: JsonObject, key: String, min: Int, max: Int): Int {
            val number = finiteNumber(json.get(key) ?: error(key), min.toDouble(), max.toDouble())
            require(number == kotlin.math.floor(number))
            return number.toInt()
        }

        private fun number(json: JsonObject, key: String, fallback: Double, min: Double, max: Double): Double =
            json.get(key)?.let { finiteNumber(it, min, max) } ?: fallback

        private fun finiteNumber(value: JsonElement, min: Double, max: Double): Double {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asDouble.also { require(it.isFinite() && it in min..max) }
        }
    }
}
