package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * Physical attachment of suspended stores: where each copy of a store touches its pylon, which point of the
 * store's model touches it, and where the store is launched from. Shared by server launches and client
 * presentation so a munition always leaves from where it is drawn.
 *
 * Aircraft mounts (hull-local blocks, all optional):
 * - `Stations` (Singles) / `LeftStations` + `RightStations` (Pairs): ordered `{Point, Face, Copies?, Stores?,
 *   ExceptStores?}`. `Point` lies on the pylon surface; `Face` is `bottom` (a store hangs underneath),
 *   `left` (a -X facing side: the store sits on the -X side) or `right`. A station serves every copy count and
 *   store unless `Copies` / `Stores` restrict it; it never serves `ExceptStores`. Right stations mirror left.
 *
 * Stores (model-file blocks in the [AircraftStoreMountAnchor] frame, all optional):
 * - `MountAnchor` is the top anchor (lug or body top); `SideMountAnchors` `{Left, Right}` are the store's own
 *   port/starboard body sides once hung nose forward; `MountAxis` is the body axis at the attachment station.
 * - With `MountAxis` the launch point is derived from the placement (station + anchor + model geometry);
 *   `LaunchOffset` stays the legacy offset from the attachment point (internal bays, stores without anchors,
 *   and every placement when `LaunchOffsetOverride` is true).
 * - `RackAdapter` `{Model, Texture, MountAnchor, Stations}`: a rack drawn with a fixed rack store. It hangs its
 *   top anchor from the mount's primary station; its stations (hull offsets, left-wing orientation, mirrored for
 *   a Pair's right position) carry the copies.
 *
 * Copies of an n-copy rack use, per physical mount position: the stations serving n copies of the store when
 * there are at least n; else the rack adapter (when it has n stations); else the legacy RackSpacing offsets
 * about the primary station. The primary station is the first unrestricted bottom station serving one copy,
 * or the mount position itself.
 */
object AircraftStoreAttachment {
    const val STATIONS = "Stations"
    const val LEFT_STATIONS = "LeftStations"
    const val RIGHT_STATIONS = "RightStations"
    const val SIDE_ANCHORS = "SideMountAnchors"
    const val AXIS = "MountAxis"
    const val LAUNCH_OFFSET_OVERRIDE = "LaunchOffsetOverride"
    const val RACK_ADAPTER = "RackAdapter"
    const val MAX_STATIONS = 8
    /** A station belongs to its mount's pylon, never to another part of the airframe. */
    const val MAX_STATION_REACH = 4.0
    const val MAX_ADAPTER_REACH = 4.0

    enum class Face(val key: String) {
        BOTTOM("bottom"), LEFT("left"), RIGHT("right");

        fun mirrored(): Face = when (this) { LEFT -> RIGHT; RIGHT -> LEFT; BOTTOM -> BOTTOM }

        companion object {
            @JvmStatic fun of(key: String): Face = entries.firstOrNull { it.key == key }
                ?: throw IllegalArgumentException("Face must be bottom, left or right")
        }
    }

    data class Station @JvmOverloads constructor(
        val point: Vec3, val face: Face = Face.BOTTOM, val copies: Set<Int>? = null,
        val stores: Set<String>? = null, val except: Set<String> = emptySet(),
    ) {
        fun serves(copies: Int, store: String?): Boolean =
            (this.copies == null || copies in this.copies) && (stores == null || store in stores) && store !in except

        fun mirrored(): Station = copy(point = Vec3(-point.x, point.y, point.z), face = face.mirrored())
    }

    /** Store anchors; [top] is `MountAnchor`, all in the model's MountAnchor frame. */
    data class Anchors @JvmOverloads constructor(
        val top: Vec3 = Vec3.ZERO, val left: Vec3? = null, val right: Vec3? = null, val axis: Vec3? = null,
        val modelForward: String = AircraftStoreModelForward.DEFAULT, val scale: Double = 1.0,
        val launchOffset: Vec3 = Vec3.ZERO, val launchOffsetOverride: Boolean = false,
    ) {
        /** A store touching a -X facing side does so with its own right side, and vice versa. */
        fun anchor(face: Face): Vec3 = when (face) {
            Face.BOTTOM -> top
            Face.LEFT -> right ?: top
            Face.RIGHT -> left ?: top
        }

        /** Hull-local offset of a model-frame offset once the model is turned nose forward and scaled. */
        fun hullDelta(model: Vec3): Vec3 {
            val d = model.scale(scale)
            return if (modelForward == AircraftStoreModelForward.POSITIVE_Z) d else Vec3(-d.x, d.y, -d.z)
        }

        fun placement(point: Vec3, face: Face): Placement {
            val anchor = anchor(face)
            val launch = if (axis != null && !launchOffsetOverride) point.add(hullDelta(axis.subtract(anchor)))
                else point.add(launchOffset)
            return Placement(point, face, anchor, launch)
        }
    }

    data class RackAdapter(val model: ResourceLocation, val texture: ResourceLocation, val anchor: Vec3,
                           val stations: List<Station>)

    /** [point]: hull point the store's [anchor] (model frame) is drawn on; [launch]: hull launch point. */
    data class Placement(val point: Vec3, val face: Face, val anchor: Vec3, val launch: Vec3) {
        fun translated(offset: Vec3): Placement = copy(point = point.add(offset), launch = launch.add(offset))
    }

    /** A rack adapter drawn with its top anchor on [point] for physical mount [position]. */
    data class AdapterPlacement(val position: Int, val point: Vec3, val adapter: RackAdapter) {
        fun translated(offset: Vec3): AdapterPlacement = copy(point = point.add(offset))
    }

    /** [placements] in fired order: copy-major, then physical position. */
    data class Layout(val placements: List<Placement>, val adapters: List<AdapterPlacement>) {
        val points: List<Vec3> get() = placements.map { it.point }
    }

    fun stationsFor(stations: List<Station>, copies: Int, store: String?): List<Station>? {
        val usable = stations.filter { it.serves(copies, store) }
        return if (usable.size >= copies) usable.take(copies) else null
    }

    fun primary(stations: List<Station>, position: Vec3): Vec3 = stations.firstOrNull {
        it.face == Face.BOTTOM && it.stores == null && (it.copies == null || 1 in it.copies)
    }?.point ?: position

    /**
     * Neutral (unswept) placements of [copies] copies on every physical position. [stations] holds one list per
     * position (Left/Right for a pair); [pair] mirrors adapter stations on the right position.
     */
    @JvmOverloads
    fun layout(positions: List<Vec3>, stations: List<List<Station>>, pair: Boolean, internal: Boolean,
               anchors: Anchors, adapter: RackAdapter?, copies: Int, spacing: Vec3, columns: Int? = null,
               store: String? = null): Layout {
        require(copies >= 1)
        if (internal) return Layout((0 until copies).flatMap { positions.map { p ->
            Placement(p, Face.BOTTOM, anchors.top, p.add(anchors.launchOffset)) } }, emptyList())
        val adapters = ArrayList<AdapterPlacement>()
        val perPosition = positions.mapIndexed { index, position ->
            val own = stations.getOrNull(index) ?: emptyList()
            val chosen = stationsFor(own, copies, store)
            when {
                chosen != null -> chosen.map { anchors.placement(it.point, it.face) }
                adapter != null && adapter.stations.size == copies -> {
                    val base = primary(own, position)
                    adapters += AdapterPlacement(index, base, adapter)
                    val mirror = pair && index == 1
                    adapter.stations.map { s ->
                        val station = if (mirror) s.mirrored() else s
                        anchors.placement(base.add(station.point), station.face)
                    }
                }
                else -> {
                    val base = primary(own, position)
                    (0 until copies).map { copy ->
                        anchors.placement(base.add(AircraftPylonRacks.offset(copy, copies, spacing, columns)), Face.BOTTOM)
                    }
                }
            }
        }
        val placements = (0 until copies).flatMap { copy -> perPosition.map { it[copy] } }
        return Layout(placements, adapters)
    }

    // --------------------------------------------------------------------------------------------- JSON
    private fun vector(value: JsonElement?, limit: Double, what: String): Vec3 {
        val v = requireNotNull(AircraftArmamentRegistry.vector(value)) { "$what must be a finite [x, y, z]" }
        require(v.length() <= limit) { "$what is too far from its reference point" }
        return v
    }

    private fun ids(element: JsonElement?, what: String): Set<String> {
        val array = requireNotNull(element as? JsonArray) { "$what must be a list of store ids" }
        require(array.size() in 1..32) { "$what must list 1 to 32 stores" }
        val out = array.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$what must be a list of store ids" }
            it.asString.also { id -> require(id.length <= 128 && ResourceLocation.tryParse(id) != null) }
        }
        require(out.distinct().size == out.size)
        return out.toSet()
    }

    fun station(json: JsonElement, reference: Vec3, reach: Double = MAX_STATION_REACH): Station {
        require(json.isJsonObject) { "A station must be an object" }
        val obj = json.asJsonObject
        require(obj.keySet().all { it in setOf("Point", "Face", "Copies", "Stores", "ExceptStores") }) {
            "Unknown station key"
        }
        val point = vector(obj["Point"], 128.0, "Station Point")
        require(point.distanceTo(reference) <= reach) { "Station Point is too far from its mount" }
        val face = obj["Face"]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Face must be a string" }
            Face.of(it.asString)
        } ?: Face.BOTTOM
        val copies = obj["Copies"]?.let { raw ->
            val array = requireNotNull(raw as? JsonArray) { "Copies must be a list" }
            require(array.size() in 1..AircraftPylonRacks.MAX_COPIES)
            array.map { it.asBigDecimal.intValueExact().also { n -> require(n in 1..AircraftPylonRacks.MAX_COPIES) } }
                .also { require(it.distinct().size == it.size) }.toSet()
        }
        return Station(point, face, copies, obj["Stores"]?.let { ids(it, "Stores") },
            obj["ExceptStores"]?.let { ids(it, "ExceptStores") } ?: emptySet())
    }

    private fun stationList(json: JsonElement?, reference: Vec3): List<Station> {
        if (json == null) return emptyList()
        val array = requireNotNull(json as? JsonArray) { "Stations must be a list" }
        require(array.size() in 1..MAX_STATIONS) { "A mount has 1 to $MAX_STATIONS stations" }
        return array.map { station(it, reference) }
    }

    /** Station lists per physical position; empty lists when the mount declares none. */
    fun stations(mount: JsonObject): List<List<Station>> {
        val positions = AircraftArmamentRegistry.mountPositions(mount)
        return if (mount.has("Position")) listOf(stationList(mount[STATIONS], positions[0]))
            else listOf(stationList(mount[LEFT_STATIONS], positions[0]), stationList(mount[RIGHT_STATIONS], positions[1]))
    }

    fun validateMount(mount: JsonObject) {
        val single = mount.has("Position")
        if (single) require(!mount.has(LEFT_STATIONS) && !mount.has(RIGHT_STATIONS)) { "A single mount uses Stations" }
        else require(!mount.has(STATIONS)) { "A paired mount uses LeftStations and RightStations" }
        val lists = stations(mount)
        if (lists.all { it.isEmpty() }) return
        require(mount["Internal"]?.asBoolean != true) { "Internal bays have no pylon stations" }
        val allowed = (mount.getAsJsonArray("AllowedStores") ?: JsonArray()).map { it.asString }.toSet()
        for (list in lists) for (station in list) {
            require((station.stores ?: emptySet()).all { it in allowed } && station.except.all { it in allowed }) {
                "Station stores must be allowed on the mount"
            }
        }
        if (!single) {
            val (left, right) = lists
            require(left.size == right.size) { "LeftStations and RightStations must mirror each other" }
            for ((l, r) in left.zip(right)) require(l.face.mirrored() == r.face && l.copies == r.copies &&
                l.stores == r.stores && l.except == r.except) { "LeftStations and RightStations must mirror each other" }
        }
    }

    fun anchors(store: JsonObject): Anchors {
        val sides = store.getAsJsonObject(SIDE_ANCHORS)
        return Anchors(AircraftStoreMountAnchor.read(store),
            sides?.get("Left")?.let(AircraftArmamentRegistry::vector),
            sides?.get("Right")?.let(AircraftArmamentRegistry::vector),
            store[AXIS]?.let(AircraftArmamentRegistry::vector),
            store[AircraftStoreModelForward.KEY]?.asString ?: AircraftStoreModelForward.DEFAULT,
            store["Scale"]?.asDouble ?: 1.0,
            AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO,
            store[LAUNCH_OFFSET_OVERRIDE]?.asBoolean == true)
    }

    fun adapter(store: JsonObject): RackAdapter? {
        val raw = store.getAsJsonObject(RACK_ADAPTER) ?: return null
        require(raw.keySet().all { it in setOf("Model", "Texture", "MountAnchor", "Stations") }) { "Unknown RackAdapter key" }
        fun location(key: String): ResourceLocation {
            val text = raw[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            requireNotNull(text) { "RackAdapter $key is required" }
            require(text.length <= 128) { "RackAdapter $key is too long" }
            return requireNotNull(ResourceLocation.tryParse(text)) { "RackAdapter $key must be a resource location" }
        }
        val anchor = raw["MountAnchor"]?.let { vector(it, AircraftStoreMountAnchor.MAX_LENGTH_BLOCKS, "RackAdapter MountAnchor") }
            ?: Vec3.ZERO
        val list = requireNotNull(raw["Stations"] as? JsonArray) { "RackAdapter Stations must be a list" }
        require(list.size() in 2..AircraftPylonRacks.MAX_COPIES) { "RackAdapter carries 2 to 12 copies" }
        val stations = list.map { station(it, Vec3.ZERO, MAX_ADAPTER_REACH) }
        require(stations.all { it.copies == null && it.stores == null && it.except.isEmpty() }) {
            "RackAdapter stations carry every copy"
        }
        return RackAdapter(location("Model"), location("Texture"), anchor, stations)
    }

    fun validateStore(store: JsonObject) {
        store[SIDE_ANCHORS]?.let {
            require(store.has("Model") && it.isJsonObject) { "$SIDE_ANCHORS describes an authored model" }
            val obj = it.asJsonObject
            require(obj.keySet() == setOf("Left", "Right")) { "$SIDE_ANCHORS needs Left and Right" }
            for (key in listOf("Left", "Right")) vector(obj[key], AircraftStoreMountAnchor.MAX_LENGTH_BLOCKS, "$SIDE_ANCHORS $key")
        }
        store[AXIS]?.let {
            require(store.has("Model")) { "$AXIS describes an authored model" }
            vector(it, AircraftStoreMountAnchor.MAX_LENGTH_BLOCKS, AXIS)
        }
        store[LAUNCH_OFFSET_OVERRIDE]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && store.has("LaunchOffset")) {
                "$LAUNCH_OFFSET_OVERRIDE is a boolean that needs LaunchOffset"
            }
        }
        store[RACK_ADAPTER]?.let {
            require(it.isJsonObject && store.has("FixedRackCount") && store.has("Model")) {
                "$RACK_ADAPTER belongs to a modelled fixed rack store"
            }
            val adapter = requireNotNull(adapter(store))
            require(adapter.stations.size == store["FixedRackCount"].asInt) {
                "$RACK_ADAPTER needs one station per rack copy"
            }
        }
    }
}
