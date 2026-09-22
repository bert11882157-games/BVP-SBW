package com.atsuishio.superbwarfare.client.aircraft

/** Cursor deltas, not frame duration, drive the gimbal; reset baselines never replay old movement. */
internal class AircraftPodAim {
    companion object {
        private const val INITIAL_PITCH_DEGREES = 30F

        fun legacyAxes(mask: Int, sensitivity: Double): net.minecraft.world.phys.Vec2 {
            val scale = sensitivity.takeIf { it.isFinite() }?.coerceIn(0.0, 2.0) ?: 0.0
            val roll = (if (mask and 8 != 0) 1 else 0) - (if (mask and 4 != 0) 1 else 0)
            val pitch = (if (mask and 1 != 0) 1 else 0) - (if (mask and 2 != 0) 1 else 0)
            return net.minecraft.world.phys.Vec2((roll * scale).toFloat(), (pitch * scale).toFloat())
        }
    }
    var yaw = 0F
        private set
    var pitch = 0F
        private set
    private var cursorX = Double.NaN
    private var cursorY = Double.NaN

    fun reset() { yaw = 0F; pitch = 0F; resetCursor() }
    fun begin(pod: AircraftPodView) {
        yaw = 0F
        // Minecraft pitch is positive downward. Never leave the authored gimbal range on entry.
        pitch = INITIAL_PITCH_DEGREES.coerceIn(pod.pitchMin, pod.pitchMax)
        resetCursor()
    }
    fun resetCursor() { cursorX = Double.NaN; cursorY = Double.NaN }

    fun sample(x: Double, y: Double, sensitivity: Double, pod: AircraftPodView) {
        if (!x.isFinite() || !y.isFinite() || !sensitivity.isFinite() || sensitivity <= 0) {
            resetCursor(); return
        }
        if (cursorX.isFinite() && cursorY.isFinite()) {
            yaw = (yaw + (x - cursorX).coerceIn(-256.0, 256.0) * sensitivity)
                .coerceIn(-pod.yawLimit.toDouble(), pod.yawLimit.toDouble()).toFloat()
            pitch = (pitch + (y - cursorY).coerceIn(-256.0, 256.0) * sensitivity)
                .coerceIn(pod.pitchMin.toDouble(), pod.pitchMax.toDouble()).toFloat()
        }
        cursorX = x; cursorY = y
    }
}
