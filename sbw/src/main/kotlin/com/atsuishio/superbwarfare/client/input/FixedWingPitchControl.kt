package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.config.client.PlaneControlConfig

/** Independent normal-pointing preference; the former default-inverted stick value is not migrated. */
object FixedWingPitchControl {
    const val DEFAULT_INVERTED = false

    @JvmStatic
    fun isInverted(): Boolean = PlaneControlConfig.INVERT_POINTING_PITCH.get()

    @JvmStatic
    fun setAndSave(inverted: Boolean) {
        if (PlaneControlConfig.INVERT_POINTING_PITCH.get() == inverted) return
        PlaneControlConfig.INVERT_POINTING_PITCH.set(inverted)
        PlaneControlConfig.INVERT_POINTING_PITCH.save()
    }

    /** Mouse Y increases downward. Only this client boundary applies the plane preference. */
    @JvmStatic
    fun apply(delta: Double): Double = if (isInverted()) -delta else delta
}
