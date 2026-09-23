package com.atsuishio.superbwarfare.api.aircraft

/** Presentation preference only; acquisition and launch continue to use independent channels. */
object AircraftSeekerDisplayPolicy {
    data class Channel(val weaponId: String, val category: String, val mode: String, val slot: String)
    fun choose(channels: List<Channel>): Channel? =
        channels.firstOrNull { it.category == "AIR_TO_GROUND" && it.mode == "GROUND_INFRARED" && it.slot == "PRIMARY" }
            ?: channels.firstOrNull { it.category == "AIR_TO_AIR" && it.mode == "INFRARED" }
            ?: channels.firstOrNull { it.category == "AIR_TO_AIR" }
            ?: channels.firstOrNull()
}
