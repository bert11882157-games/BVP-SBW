package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** Counts complete fitted stores; bomb Capacity contributes to the physical weapon limit. */
object AircraftPylonRacks {
    const val MAX_COPIES = 12
    const val MAX_BAY_COPIES = 512
    /** Client loadout edits may retain fitted copies, but cannot choose a new copy count. */
    fun fixedSelectionCopies(currentStore: String?, chosenStore: String, currentCopies: Int,
                             requestedCopies: Int?, authoredCopies: Int? = null): Int {
        require(authoredCopies == null || authoredCopies in 1..MAX_COPIES)
        val copies = authoredCopies ?: if (currentStore == chosenStore) currentCopies.coerceAtLeast(1) else 1
        require(requestedCopies == null || requestedCopies == copies) { "Rack quantity cannot be edited." }
        return copies
    }
    /** Internal bays expose quantity controls; external mounts retain authored fixed racks. */
    fun selectionCopies(currentStore: String?, chosenStore: String, currentCopies: Int,
                        requestedCopies: Int?, authoredCopies: Int? = null, internal: Boolean = false): Int {
        if (!internal) return fixedSelectionCopies(currentStore, chosenStore, currentCopies,
            requestedCopies, authoredCopies)
        require(authoredCopies == null) { "Internal bay stores cannot use fixed external racks." }
        val copies = requestedCopies ?: if (currentStore == chosenStore) currentCopies else 1
        require(copies in 1..MAX_BAY_COPIES) { "Invalid internal bay quantity." }
        return copies
    }
    @JvmOverloads
    fun maxCopies(aircraftLimit: Int, mountLimit: Int, storeLimit: Int, category: String,
                  capacity: Int, massKg: Double, maxPylonMassKg: Double, internal: Boolean = false,
                  rackMassKg: Double = 0.0): Int {
        if (internal && category in setOf("BOMB", "CRUISE", "AIR_TO_AIR", "AIR_TO_GROUND", "LASER_GUIDED", "ANTI_RADIATION"))
            return if (massKg > 0.0 && maxPylonMassKg.isFinite())
                (maxPylonMassKg / (massKg * capacity.coerceAtLeast(1))).toInt().coerceIn(1, MAX_BAY_COPIES)
            else 1
        if (category !in setOf("BOMB", "AIR_TO_AIR", "AIR_TO_GROUND")) return 1
        val rounds = capacity.coerceAtLeast(1)
        val weaponLimit = min(aircraftLimit, min(mountLimit, storeLimit)).coerceIn(1, MAX_COPIES)
        val massLimit = if (massKg > 0.0 && maxPylonMassKg.isFinite())
            ((maxPylonMassKg - rackMassKg).coerceAtLeast(0.0) / (massKg * rounds)).toInt().coerceAtLeast(1) else MAX_COPIES
        return min((weaponLimit / rounds).coerceAtLeast(1), massLimit)
    }

    fun maxCopies(definition: JsonObject, mount: JsonObject, store: JsonObject): Int = maxCopies(
        definition["MaxWeaponsPerPylon"]?.asInt ?: 1,
        mount["MaxWeaponsPerPylon"]?.asInt ?: MAX_COPIES,
        store["MaxPerPylon"]?.asInt ?: 1, store["Category"].asString,
        store["Capacity"]?.asInt ?: 1, store["MassKg"]?.asDouble ?: 0.0,
        mount["MaxPylonMassKg"]?.asDouble ?: definition["MaxPylonMassKg"]?.asDouble ?: Double.POSITIVE_INFINITY,
        mount["Internal"]?.asBoolean == true, store["RackMassKg"]?.asDouble ?: 0.0)

    /** Lateral columns, then rows below/aft in hull-local blocks. No frame-dependent allocation. */
    @JvmOverloads
    fun offset(copy: Int, copies: Int, spacing: Vec3, rackColumns: Int? = null): Vec3 {
        require(copies in 1..MAX_COPIES && copy in 0 until copies)
        require(rackColumns == null || rackColumns in 1..MAX_COPIES)
        if (copies == 3 && rackColumns == null) return when (copy) {
            0 -> Vec3(-spacing.x * 0.5, 0.0, 0.0)
            1 -> Vec3(spacing.x * 0.5, 0.0, 0.0)
            else -> Vec3(0.0, -spacing.y, 0.0)
        }
        val columns = min(copies, rackColumns ?: 3)
        val row = copy / columns
        val rowSize = min(columns, copies - row * columns)
        return Vec3((copy % columns - (rowSize - 1) * 0.5) * spacing.x,
            -row * spacing.y, -row * spacing.z)
    }

    fun spacing(store: JsonObject): Vec3 = AircraftArmamentRegistry.vector(store["RackSpacing"])
        ?: Vec3(0.6, 0.4, 0.0)

    /** The registry id of a catalogue store object (station store filters are keyed by id). */
    private fun registeredId(store: JsonObject): String? =
        AircraftArmamentRegistry.stores.entries.firstOrNull { it.value === store }?.key?.toString()

    /** Neutral copy placements on pylon stations, a rack adapter or legacy offsets; see [AircraftStoreAttachment]. */
    @JvmOverloads
    fun layout(mount: JsonObject, store: JsonObject, copies: Int, storeId: String? = registeredId(store)): AircraftStoreAttachment.Layout =
        AircraftStoreAttachment.layout(AircraftArmamentRegistry.mountPositions(mount), AircraftStoreAttachment.stations(mount),
            mount.has("Left"), mount["Internal"]?.asBoolean == true, AircraftStoreAttachment.anchors(store),
            AircraftStoreAttachment.adapter(store), copies, spacing(store), store["RackColumns"]?.asInt, storeId)

    private fun placement(mount: JsonObject, store: JsonObject, copies: Int, fired: Int,
                          storeId: String?): AircraftStoreAttachment.Placement {
        val positions = AircraftArmamentRegistry.mountPositions(mount)
        val capacity = store["Capacity"]?.asInt ?: 1
        require(fired in 0 until capacity * positions.size * copies)
        val slot = if (mount["Internal"]?.asBoolean == true) fired % positions.size
            else ((fired / positions.size) % copies) * positions.size + fired % positions.size
        return layout(mount, store, if (mount["Internal"]?.asBoolean == true) 1 else copies, storeId).placements[slot]
    }

    /** Where the round [fired] touches its pylon (the drawn attachment point), before any wing sweep. */
    @JvmOverloads
    fun launchPosition(mount: JsonObject, store: JsonObject, copies: Int, fired: Int, storeId: String? = registeredId(store)): Vec3 =
        placement(mount, store, copies, fired, storeId).point

    /** Hull offset from [launchPosition] to the round's launch point (derived from its anchors, or LaunchOffset). */
    @JvmOverloads
    fun launchOffset(mount: JsonObject, store: JsonObject, copies: Int, fired: Int, storeId: String? = registeredId(store)): Vec3 =
        placement(mount, store, copies, fired, storeId).let { it.launch.subtract(it.point) }

    /** Neutral launch point of round [fired]: [launchPosition] plus [launchOffset]. */
    @JvmOverloads
    fun launchPoint(mount: JsonObject, store: JsonObject, copies: Int, fired: Int, storeId: String? = registeredId(store)): Vec3 =
        placement(mount, store, copies, fired, storeId).launch
}
