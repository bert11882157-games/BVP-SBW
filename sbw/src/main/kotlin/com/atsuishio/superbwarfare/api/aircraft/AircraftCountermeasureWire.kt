package com.atsuishio.superbwarfare.api.aircraft

/** Protocol 37: longer chaff programs require more than the former four level bits. */
object AircraftCountermeasureWire {
    fun pack(flares: Int, chaff: Int, threat: Int, emitting: Boolean): Int =
        flares.coerceIn(0, 255) or (chaff.coerceIn(0, 65535) shl 8) or
            (threat.coerceIn(0, 3) shl 24) or (if (emitting) 1 shl 26 else 0)
    fun flares(value: Int): Int = value and 255
    fun chaff(value: Int): Int = (value ushr 8) and 65535
    fun threat(value: Int): Int = (value ushr 24) and 3
    fun emitting(value: Int): Boolean = (value and (1 shl 26)) != 0
}
