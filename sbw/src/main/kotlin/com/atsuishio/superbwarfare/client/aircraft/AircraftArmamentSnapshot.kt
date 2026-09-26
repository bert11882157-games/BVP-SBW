package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftPylonRacks
import com.atsuishio.superbwarfare.api.aircraft.AircraftMountSweep
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreAttachment
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreModelForward
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreMountAnchor
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
    val massKg: Double = 0.0, val maxPerPylon: Int = 1,
    val rackSpacing: Vec3 = Vec3(0.6, 0.4, 0.0),
    val fixedRackCount: Int? = null,
    val guidanceMode: String = "",
    val ammoItem: ResourceLocation? = null,
    val rackColumns: Int? = null,
    val rackMassKg: Double = 0.0,
    /** Model-file end that is the store's nose; see [AircraftStoreModelForward]. */
    val modelForward: String = AircraftStoreModelForward.DEFAULT,
    /** Model-file point, in blocks, placed on the mount; see [AircraftStoreMountAnchor]. */
    val mountAnchor: Vec3 = Vec3.ZERO,
    /** Top/side anchors, body axis and launch offset; see [AircraftStoreAttachment]. */
    val anchors: AircraftStoreAttachment.Anchors = AircraftStoreAttachment.Anchors(mountAnchor, modelForward = modelForward, scale = scale),
    /** Generated rack drawn with a fixed rack store where the pylon offers too few stations. */
    val rackAdapter: AircraftStoreAttachment.RackAdapter? = null,
) {
    /** Degrees about the vertical axis through the mount point that put the nose forward. */
    val mountYawDegrees: Float get() = AircraftStoreModelForward.mountYawDegrees(modelForward)
    val visualOnly: Boolean get() = (category in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION") && !guidedAirToAir) || category == "VISUAL_ONLY"
    val categoryLabel: String get() = when (category) {
        "AIR_TO_AIR" -> if (guidedAirToAir) "Air-to-air guided missile" else "Air-to-air · visual only"
        "ANTI_RADIATION" -> "Anti-radiation missile"
        "AIR_TO_GROUND" -> "Guided air-to-ground munition"
        "CRUISE" -> "Cruise missile"
        "VISUAL_ONLY" -> "Visual only"
        "LASER_GUIDED", "COMMAND_GUIDED" -> "Guided air-to-ground munition"
        "GUN_POD" -> "Gun pod"
        "ROCKET_POD" -> "Unguided rocket pod"
        else -> "Bomb"
    }
}

interface AircraftMountView {
    val id: String
    val name: String
    val positions: List<Vec3>
    val allowed: List<String>
    val groups: Map<String, List<String>>
    val maxWeaponsPerPylon: Int
    val maxPylonMassKg: Double
    val internal: Boolean get() = false
    val quantitySelectable: Boolean get() = internal
    val sweepFrames: List<AircraftMountSweep> get() = emptyList()
    /** Pylon stations per physical position (Left/Right for a pair); see [AircraftStoreAttachment]. */
    val stations: List<List<AircraftStoreAttachment.Station>> get() = emptyList()
    fun position(index: Int, speed: Double): Vec3 = sweepFrames.getOrNull(index)?.position(positions[index],speed) ?: positions[index]
}

data class AircraftPairView(
    override val id: String, override val name: String, val left: Vec3, val right: Vec3,
    override val allowed: List<String>, override val groups: Map<String, List<String>>,
    override val maxWeaponsPerPylon: Int = AircraftPylonRacks.MAX_COPIES,
    override val maxPylonMassKg: Double = Double.POSITIVE_INFINITY,
    override val sweepFrames: List<AircraftMountSweep> = emptyList(),
    override val stations: List<List<AircraftStoreAttachment.Station>> = emptyList(),
) : AircraftMountView {
    override val positions: List<Vec3> = Collections.unmodifiableList(listOf(left, right))
}

data class AircraftSingleView(
    override val id: String, override val name: String, val position: Vec3,
    override val allowed: List<String>, override val groups: Map<String, List<String>>,
    override val maxWeaponsPerPylon: Int = AircraftPylonRacks.MAX_COPIES,
    override val maxPylonMassKg: Double = Double.POSITIVE_INFINITY,
    override val internal: Boolean = false,
    override val quantitySelectable: Boolean = internal,
    override val sweepFrames: List<AircraftMountSweep> = emptyList(),
    override val stations: List<List<AircraftStoreAttachment.Station>> = emptyList(),
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
    val activeAam: Boolean get() = category in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "BOMB") && weaponId.isNotBlank() &&
        guidanceMode in setOf("INFRARED", "ACTIVE_RADAR", "SEMI_ACTIVE_RADAR", "ANTI_RADIATION", "GROUND_INFRARED") &&
        coneDegrees in 1.0..60.0 && range in 16.0..4096.0 && status != "UNAVAILABLE"
}

data class AircraftDefinitionView @JvmOverloads constructor(
    val name: String, val builtIn: List<String>, val pairs: List<AircraftPairView>,
    val pod: AircraftPodView?, val neutralGroups: Map<String, List<String>>,
    val singles: List<AircraftSingleView> = emptyList(),
    val maxPayloadKg: Double = 0.0, val maxWeaponsPerPylon: Int = 1,
) {
    val mounts: List<AircraftMountView> = Collections.unmodifiableList(pairs + singles)
}

data class AircraftArmamentSnapshot(
    val vehicle: UUID, val entityId: Int, val revision: Long,
    val definition: AircraftDefinitionView, val stores: Map<String, AircraftStoreView>,
    val selections: Map<String, String>, val presets: Map<String, Map<String, String>>,
    val point: Vec3?, val podActive: Boolean, val message: String,
    val fired: Map<String, Int> = emptyMap(), val seekPair: String = "", val seekStatus: Int = 0,
    val seek: AircraftSeekView? = null, val counts: Map<String, Int> = emptyMap(),
) {
    fun payloadKg(choices: Map<String, String> = selections, quantities: Map<String, Int> = counts): Double = definition.mounts.sumOf { mount ->
        val store = stores[choices[mount.id]] ?: return@sumOf 0.0
        (store.massKg * (store.capacity ?: 1) * (quantities[mount.id] ?: store.fixedRackCount ?: 1) +
            store.rackMassKg) * mount.positions.size
    }
    fun maxCopies(mount: AircraftMountView, store: AircraftStoreView): Int = AircraftPylonRacks.maxCopies(
        definition.maxWeaponsPerPylon, mount.maxWeaponsPerPylon, store.maxPerPylon,
        store.category, store.capacity ?: 1, store.massKg, mount.maxPylonMassKg, mount.internal, store.rackMassKg)

    /** Physical rack placements are shared by near/far presentation and server launch ordering. */
    private val rackLayout: Map<String, AircraftStoreAttachment.Layout> by lazy {
        definition.mounts.associate { mount ->
            val store = stores[selections[mount.id]]
            val copies = if (store == null) 1 else (counts[mount.id] ?: 1).coerceIn(1, maxCopies(mount, store))
            mount.id to if (store == null) AircraftStoreAttachment.Layout(emptyList(), emptyList())
                else AircraftStoreAttachment.layout(mount.positions, mount.stations, mount is AircraftPairView,
                    mount.internal, store.anchors, store.rackAdapter, copies, store.rackSpacing, store.rackColumns, store.id)
        }
    }
    private fun sweepOffsets(mount: AircraftMountView, speed: Double): List<Vec3>? =
        if (mount.sweepFrames.isEmpty()) null
        else mount.positions.indices.map { mount.position(it, speed).subtract(mount.positions[it]) }
    /** Where each copy's attachment point is drawn, in fired order (copy-major, then position). */
    fun rackPositions(mount: AircraftMountView): List<Vec3> = rackLayout[mount.id]?.points ?: emptyList()
    fun rackPositions(mount: AircraftMountView, speed: Double): List<Vec3> = rackPlacements(mount, speed).map { it.point }
    fun rackPlacements(mount: AircraftMountView): List<AircraftStoreAttachment.Placement> =
        rackLayout[mount.id]?.placements ?: emptyList()
    fun rackPlacements(mount: AircraftMountView, speed: Double): List<AircraftStoreAttachment.Placement> {
        val neutral = rackPlacements(mount)
        val offsets = sweepOffsets(mount, speed) ?: return neutral
        return neutral.mapIndexed { index, placement -> placement.translated(offsets[index % offsets.size]) }
    }
    /** Generated rack adapters drawn with the selected rack store (at most one per physical position). */
    fun rackAdapters(mount: AircraftMountView, speed: Double): List<AircraftStoreAttachment.AdapterPlacement> {
        val neutral = rackLayout[mount.id]?.adapters ?: emptyList()
        val offsets = sweepOffsets(mount, speed) ?: return neutral
        return neutral.map { it.translated(offsets[it.position]) }
    }
    /** Launches alternate across the mount's physical positions. Pods stay after firing. */
    fun storePresent(mount: AircraftMountView, position: Int): Boolean {
        val store = stores[selections[mount.id]] ?: return false
        if (store.category !in RELEASED_ONE_BY_ONE && !store.guidedAirToAir) return true
        val capacity = store.capacity ?: 1
        return (fired[mount.id] ?: 0) < (capacity - 1) * mount.positions.size * (counts[mount.id] ?: 1) + position + 1
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
        /** Stores whose munitions leave the rack one at a time (checked per store, per frame, while drawing). */
        private val RELEASED_ONE_BY_ONE = setOf("LASER_GUIDED", "COMMAND_GUIDED", "BOMB", "CRUISE")
        private val categories = setOf("COMMAND_GUIDED", "LASER_GUIDED", "GUN_POD", "BOMB", "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE", "ROCKET_POD", "VISUAL_ONLY")

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
                    allowed, groups, optionalInteger(pair, "MaxWeaponsPerPylon", 12),
                    number(pair, "MaxPylonMassKg", number(raw, "MaxPylonMassKg", Double.POSITIVE_INFINITY, 0.1, 100000.0), 0.1, 100000.0),
                    AircraftMountSweep.decode(pair,2), stations(pair))
            }.also { require(it.map(AircraftPairView::id).distinct().size == it.size) }
            val singles = array(raw, "Singles", 16).map { element ->
                val mount = element.asJsonObject
                require(!mount.has("Left") && !mount.has("Right"))
                val allowed = strings(mount, "AllowedStores", 256).also { it.forEach(::resource) }
                val groups = groups(mount, "StoreGroups")
                require(groups.keys.all { it in allowed })
                AircraftSingleView(string(mount, "Id", 64), string(mount, "Name", 96),
                    vector(mount.get("Position"), 128.0), allowed, groups,
                    optionalInteger(mount, "MaxWeaponsPerPylon", 12),
                    number(mount, "MaxPylonMassKg", number(raw, "MaxPylonMassKg", Double.POSITIVE_INFINITY, 0.1, 100000.0), 0.1, 100000.0),
                    mount["Internal"]?.asBoolean == true,
                    mount["QuantitySelectable"]?.asBoolean ?: (mount["Internal"]?.asBoolean == true),
                    AircraftMountSweep.decode(mount,1), stations(mount))
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
                groups(raw, "StoreGroups"), Collections.unmodifiableList(singles),
                number(raw, "MaxPayloadKg", 0.0, 0.0, 100000.0), optionalInteger(raw, "MaxWeaponsPerPylon", 1))
            val rawStores = json.getAsJsonObject("Stores") ?: error("stores")
            require(rawStores.size() <= 256)
            val stores = rawStores.entrySet().associate { (id, value) ->
                resource(id)
                val store = value.asJsonObject
                require(integer(store, "Schema", 1, 1) == 1)
                AircraftStoreAttachment.validateStore(store)
                val category = string(store, "Category", 32).also { require(it in categories) }
                id to AircraftStoreView(id, string(store, "Name", 96), category,
                    optionalResource(store, "Item"), optionalResource(store, "Model"),
                    optionalResource(store, "Texture"), number(store, "Scale", 1.0, 0.001, 64.0),
                    store.get("Capacity")?.let { integer(store, "Capacity", 1, 100000) },
                    category in setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION") && store.has("Guidance"),
                    number(store, "MassKg", 0.0, 0.0, 50000.0), optionalInteger(store, "MaxPerPylon", 1),
                    store["RackSpacing"]?.let { vector(it, 8.0) } ?: Vec3(0.6, 0.4, 0.0),
                    store["FixedRackCount"]?.let { integer(store, "FixedRackCount", 2, AircraftPylonRacks.MAX_COPIES) },
                    com.atsuishio.superbwarfare.api.aircraft.AircraftGuidanceLabels.mode(store), optionalResource(store, "AmmoItem"),
                    store["RackColumns"]?.let { integer(store, "RackColumns", 1, AircraftPylonRacks.MAX_COPIES) },
                    number(store,"RackMassKg",0.0,0.0,2000.0),
                    store[AircraftStoreModelForward.KEY]?.asString?.also {
                        require(it in AircraftStoreModelForward.values)
                    } ?: AircraftStoreModelForward.DEFAULT,
                    store[AircraftStoreMountAnchor.KEY]?.let {
                        vector(it, AircraftStoreMountAnchor.MAX_LENGTH_BLOCKS)
                    } ?: Vec3.ZERO,
                    AircraftStoreAttachment.anchors(store).also {
                        require(it.scale in 0.001..64.0 && it.launchOffset.length() <= 8.0)
                    },
                    AircraftStoreAttachment.adapter(store))
            }
            val selections = selections(json.getAsJsonObject("Selections"), definition)
            val rawCounts = json.getAsJsonObject("Counts") ?: JsonObject()
            require(rawCounts.size() <= selections.size)
            val counts = rawCounts.entrySet().associate { (key, _) ->
                val mount = definition.mounts.firstOrNull { it.id == key } ?: error("unknown rack")
                val store = stores[selections[key]] ?: error("empty rack")
                val maximum = AircraftPylonRacks.maxCopies(definition.maxWeaponsPerPylon, mount.maxWeaponsPerPylon,
                    store.maxPerPylon, store.category, store.capacity ?: 1, store.massKg, mount.maxPylonMassKg, mount.internal, store.rackMassKg)
                key to integer(rawCounts, key, 1, maximum)
            }
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
                json.getAsJsonObject("Seek")?.let(::decodeSeek), Collections.unmodifiableMap(counts))
        } catch (_: RuntimeException) { null }

        /** Station lists per position; the definition already passed the registry's validation. */
        private fun stations(mount: JsonObject): List<List<AircraftStoreAttachment.Station>> {
            AircraftStoreAttachment.validateMount(mount)
            return AircraftStoreAttachment.stations(mount).map { Collections.unmodifiableList(it) }
        }

        private fun optionalInteger(json: JsonObject, key: String, default: Int): Int =
            if (json.has(key)) integer(json, key, 1, AircraftPylonRacks.MAX_COPIES) else default

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
            val block = json.get("TargetType")?.asString == "BLOCK"
            require(position == null || target != null || block)
            require(target == null || position != null)
            require((target == null && !block) || weapon.isNotBlank())
            require((status == "READY") == ready)
            require(!ready || (position != null && status == "READY" && progress == 1.0))
            fun optionalString(key: String) = if (json.has(key)) string(json, key, 32, true) else ""
            val category = optionalString("Category")
            val mode = optionalString("GuidanceMode")
            val slot = optionalString("Slot")
            require(category.isEmpty() || category in categories)
            require(mode in setOf("", "INFRARED", "ACTIVE_RADAR", "SEMI_ACTIVE_RADAR", "ANTI_RADIATION", "GROUND_INFRARED"))
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
