package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** Counts complete fitted stores; bomb Capacity contributes to the physical weapon limit. */
object AircraftPylonRacks {
    const val MAX_COPIES = 12
    const val MAX_BAY_COPIES = 512
    @JvmOverloads
    fun maxCopies(aircraftLimit: Int, mountLimit: Int, storeLimit: Int, category: String,
                  capacity: Int, massKg: Double, maxPylonMassKg: Double, internal: Boolean = false): Int {
        if (internal && category in setOf("BOMB", "CRUISE", "AIR_TO_AIR", "LASER_GUIDED", "ANTI_RADIATION"))
            return if (massKg > 0.0 && maxPylonMassKg.isFinite())
                (maxPylonMassKg / (massKg * capacity.coerceAtLeast(1))).toInt().coerceIn(1, MAX_BAY_COPIES) else 1
        if (category !in setOf("BOMB", "AIR_TO_AIR")) return 1
        val rounds = capacity.coerceAtLeast(1)
        val weaponLimit = min(aircraftLimit, min(mountLimit, storeLimit)).coerceIn(1, MAX_COPIES)
        val massLimit = if (massKg > 0.0 && maxPylonMassKg.isFinite())
            (maxPylonMassKg / (massKg * rounds)).toInt().coerceAtLeast(1) else MAX_COPIES
        return min((weaponLimit / rounds).coerceAtLeast(1), massLimit)
    }

    fun maxCopies(definition: JsonObject, mount: JsonObject, store: JsonObject): Int = maxCopies(
        definition["MaxWeaponsPerPylon"]?.asInt ?: 1,
        mount["MaxWeaponsPerPylon"]?.asInt ?: MAX_COPIES,
        store["MaxPerPylon"]?.asInt ?: 1, store["Category"].asString,
        store["Capacity"]?.asInt ?: 1, store["MassKg"]?.asDouble ?: 0.0,
        mount["MaxPylonMassKg"]?.asDouble ?: definition["MaxPylonMassKg"]?.asDouble ?: Double.POSITIVE_INFINITY,
        mount["Internal"]?.asBoolean == true)

    /** Lateral columns, then rows below/aft in hull-local blocks. No frame-dependent allocation. */
    fun offset(copy: Int, copies: Int, spacing: Vec3): Vec3 {
        require(copies in 1..MAX_COPIES && copy in 0 until copies)
        val columns = min(copies, 3)
        val row = copy / columns
        val rowSize = min(columns, copies - row * columns)
        return Vec3((copy % columns - (rowSize - 1) * 0.5) * spacing.x,
            -row * spacing.y, -row * spacing.z)
    }

    fun spacing(store: JsonObject): Vec3 = AircraftArmamentRegistry.vector(store["RackSpacing"])
        ?: Vec3(0.6, 0.4, 0.0)

    fun launchPosition(mount: JsonObject, store: JsonObject, copies: Int, fired: Int): Vec3 {
        val positions = AircraftArmamentRegistry.mountPositions(mount)
        val capacity = store["Capacity"]?.asInt ?: 1
        require(fired in 0 until capacity * positions.size * copies)
        if (mount["Internal"]?.asBoolean == true) return positions[fired % positions.size]
        val copy = (fired / positions.size) % copies
        return positions[fired % positions.size].add(offset(copy, copies, spacing(store)))
    }
}
