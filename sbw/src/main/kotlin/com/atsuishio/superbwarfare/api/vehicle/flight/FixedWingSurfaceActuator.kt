package com.atsuishio.superbwarfare.api.vehicle.flight

/** Finite normalized travel; torque authority is applied separately by the aerodynamic model. */
object FixedWingSurfaceActuator {
    fun advance(current: Double, requested: Double, travelPerSecond: Double, seconds: Double): Double {
        require(current.isFinite() && requested.isFinite())
        require(travelPerSecond.isFinite() && travelPerSecond > 0.0)
        require(seconds.isFinite() && seconds >= 0.0)
        val start = current.coerceIn(-1.0, 1.0)
        val target = requested.coerceIn(-1.0, 1.0)
        val distance = travelPerSecond * seconds
        return start + (target - start).coerceIn(-distance, distance)
    }
}
