package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject

object AircraftGuidanceLabels {
    fun mode(store: JsonObject): String = store.getAsJsonObject("Guidance")?.let {
        it["Presentation"]?.asString ?: it["Mode"]?.asString
    }
        ?: store.getAsJsonObject("CommandGuidance")?.get("Mode")?.asString
        ?: store.getAsJsonObject("Bomb")?.get("Mode")?.asString
        ?: when (store["Category"]?.asString) {
            "LASER_GUIDED" -> "LASER"
            "ROCKET_POD", "BOMB" -> "DUMB"
            else -> ""
        }

    fun short(mode: String): String = when (mode.uppercase(java.util.Locale.ROOT)) {
        "INFRARED", "GROUND_INFRARED" -> "IR"
        "FIRE_AND_FORGET" -> "F&F"
        "ACTIVE_RADAR", "ACTIVE_SURFACE_RADAR" -> "ARH"
        "SEMI_ACTIVE_RADAR" -> "SARH"
        "ANTI_RADIATION" -> "ARM"
        "LASER_GUIDED", "LASER" -> "LASER"
        "GPS", "GPS_GUIDED", "CRUISE" -> "GPS"
        "DUMB", "UNGUIDED" -> "DUMB"
        else -> mode.uppercase(java.util.Locale.ROOT).take(8)
    }

    fun description(mode: String): String = when (short(mode)) {
        "IR" -> "Infrared homing"
        "F&F" -> "Fire and forget"
        "TV" -> "Electro-optical homing"
        "MCLOS" -> "Manual command · arrow keys steer"
        "SACLOS" -> "Command guided · keep sight on target"
        "ARH" -> "Active radar homing"
        "SARH" -> "Semi-active radar homing"
        "ARM" -> "Passive radar homing"
        "LASER" -> "Laser guided"
        "GPS" -> "GPS guided"
        "DUMB" -> "Unguided"
        "" -> "Direct fire"
        else -> short(mode)
    }
}
