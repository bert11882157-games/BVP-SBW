package com.atsuishio.superbwarfare.api.vehicle.render

/** Ambient fallback only where the client has no terrain light data. */
internal object FarVehicleLighting {
    fun unloaded(sky: Boolean, hasSkyLight: Boolean, y: Int, minY: Int): Int =
        if (sky && hasSkyLight && y >= minY) 15 else 0
}
