package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.config.client.PlaneControlConfig

/** Persistent client joystick multiplier, applied only at the fixed-wing packet boundary. */
object FixedWingJoystickSensitivity {
    const val MINIMUM = 0.1
    const val MAXIMUM = 2.0
    const val DEFAULT = 1.0

    @JvmStatic
    fun normalize(value: Double): Double =
        if (value.isFinite()) value.coerceIn(MINIMUM, MAXIMUM) else DEFAULT

    @JvmStatic
    fun get(): Double = normalize(PlaneControlConfig.JOYSTICK_SENSITIVITY.get())

    @JvmStatic
    fun setAndSave(value: Double) {
        val normalized = normalize(value)
        if (PlaneControlConfig.JOYSTICK_SENSITIVITY.get() == normalized) return
        PlaneControlConfig.JOYSTICK_SENSITIVITY.set(normalized)
        PlaneControlConfig.JOYSTICK_SENSITIVITY.save()
    }

    @JvmStatic
    fun scale(delta: Double, multiplier: Double): Double = delta * normalize(multiplier)
}
