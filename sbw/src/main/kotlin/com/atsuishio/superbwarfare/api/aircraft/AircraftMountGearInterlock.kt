package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject

/**
 * Mount key for stations whose release path is blocked by the extended landing gear, such as a
 * centerline station between the main gear. The server rejects a release until the gear is
 * fully retracted; fitting and presentation are unaffected.
 */
object AircraftMountGearInterlock {
    const val KEY = "RequiresRetractedGear"

    fun required(mount: JsonObject): Boolean = mount[KEY]?.asBoolean == true
}
