package com.atsuishio.superbwarfare.tools.blast

import com.google.gson.JsonElement

/**
 * Parser for `data/<namespace>/blast/<name>.json` TNT-equivalent default tables:
 * `{"entries": {"superbwarfare:c4": 0.57, "superbwarfare:mk_82": 89.0, ...}}`.
 *
 * Keys are the munition inventory row ids of munitions that are spawned without GunData: entity type ids
 * (`superbwarfare:mortar_shell`), config-backed munitions (`superbwarfare:hand_grenade`), items
 * (`superbwarfare:medium_rocket_he`) and special keys (`superbwarfare:perk/firefly`,
 * `superbwarfare:cannon_shell#cm_submunition`, `superbwarfare:auto_aimable#air_burst`).
 * A null or 0 value means "no TNT equivalent" (legacy blast).
 */
object TntDefaultTable {
    const val MAX_KG = 100_000.0

    /** Returns the valid positive entries; throws on a malformed file so the loader can report it. */
    @JvmStatic
    fun parse(json: JsonElement): Map<String, Double> {
        require(json.isJsonObject) { "TNT defaults must be a JSON object" }
        val entries = json.asJsonObject.get("entries") ?: return emptyMap()
        require(entries.isJsonObject) { "\"entries\" must be an object" }
        val result = LinkedHashMap<String, Double>()
        for ((key, value) in entries.asJsonObject.entrySet()) {
            require(key.isNotBlank() && key.length <= 256) { "Invalid TNT defaults key '$key'" }
            if (value == null || value.isJsonNull) continue
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "TNT default for $key must be a number" }
            val kg = value.asDouble
            require(kg.isFinite() && kg in 0.0..MAX_KG) { "TNT default for $key must be within 0..$MAX_KG kg" }
            if (kg > 0.0) result[key] = kg
        }
        return result
    }

    /** A server config override (>= 0) wins; -1 (any negative) defers to the data table. */
    @JvmStatic
    fun configuredOrTable(configValue: Double, tableValue: Double?): Double = when {
        configValue.isFinite() && configValue >= 0.0 -> configValue.coerceAtMost(MAX_KG)
        else -> tableValue ?: 0.0
    }
}
