package com.atsuishio.superbwarfare.api.vehicle.render

/** Accepted visual controls only; no pose, gameplay input, or flight-model state is applied. */
data class FarFixedWingVisualState private constructor(
    val sequence: Int,
    val serverTick: Long,
    val elevator: Float,
    val aileron: Float,
    val rudder: Float,
    val throttle: Double,
    val airbrake: Float = 0F,
) {
    init {
        require(serverTick >= 0L)
        require(elevator.isFinite() && elevator in -1F..1F)
        require(aileron.isFinite() && aileron in -1F..1F)
        require(rudder.isFinite() && rudder in -1F..1F)
        require(throttle.isFinite() && throttle in 0.0..1.0)
        require(airbrake.isFinite() && airbrake in 0F..1F)
    }

    fun encode(): String = "2;$sequence;$serverTick;$elevator;$aileron;$rudder;$throttle;$airbrake"

    companion object {
        const val VISUAL_KEY = "sbw.fixed_wing_controls_v1"
        const val MAX_TEXT_LENGTH = 192

        @JvmStatic
        @JvmOverloads
        fun create(sequence: Int, serverTick: Long, elevator: Float, aileron: Float,
                   rudder: Float, throttle: Double, airbrake: Float = 0F): FarFixedWingVisualState? {
            if (serverTick < 0L || !elevator.isFinite() || elevator !in -1F..1F ||
                !aileron.isFinite() || aileron !in -1F..1F ||
                !rudder.isFinite() || rudder !in -1F..1F ||
                !throttle.isFinite() || throttle !in 0.0..1.0 ||
                !airbrake.isFinite() || airbrake !in 0F..1F) return null
            return FarFixedWingVisualState(sequence, serverTick, elevator, aileron, rudder, throttle, airbrake)
        }

        @JvmStatic
        fun decode(text: String?): FarFixedWingVisualState? {
            if (text == null || text.length > MAX_TEXT_LENGTH) return null
            val fields = text.split(';')
            val legacy = fields.size == 7 && fields[0] == "1"
            if (!legacy && (fields.size != 8 || fields[0] != "2")) return null
            return create(fields[1].toIntOrNull() ?: return null,
                fields[2].toLongOrNull() ?: return null,
                fields[3].toFloatOrNull() ?: return null,
                fields[4].toFloatOrNull() ?: return null,
                fields[5].toFloatOrNull() ?: return null,
                fields[6].toDoubleOrNull() ?: return null,
                if (legacy) 0F else fields[7].toFloatOrNull() ?: return null)
        }

        /** Uses the enclosing far/chassis clock; omission or source reset never holds old controls. */
        @JvmStatic
        fun interpolate(previous: FarFixedWingVisualState?, current: FarFixedWingVisualState?,
                        alpha: Float): FarFixedWingVisualState? {
            if (current == null || !alpha.isFinite()) return null
            if (previous == null || current.serverTick <= previous.serverTick ||
                current.sequence - previous.sequence <= 0) return current
            val a = alpha.coerceIn(0F, 1F)
            if (a == 0F) return previous
            if (a == 1F) return current
            fun linear(x: Float, y: Float) = x + a * (y - x)
            return create(current.sequence, current.serverTick,
                linear(previous.elevator, current.elevator),
                linear(previous.aileron, current.aileron),
                linear(previous.rudder, current.rudder),
                previous.throttle + a.toDouble() * (current.throttle - previous.throttle),
                linear(previous.airbrake, current.airbrake))
        }
    }
}
