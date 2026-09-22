package com.atsuishio.superbwarfare.api.vehicle.flight

/** Finite accepted deflections: elevator nose-up, aileron right-bank, rudder nose-right. */
data class FixedWingControlSurfaceSnapshot @JvmOverloads constructor(
    val serverTick: Long,
    val elevator: Float,
    val aileron: Float,
    val rudder: Float,
    val airbrake: Float,
    val throttle: Float,
    val afterburnerActive: Boolean,
    val wheelBrakeActive: Boolean = false,
) {
    init {
        require(serverTick >= 0L)
        require(elevator.isFinite() && elevator in -1F..1F)
        require(aileron.isFinite() && aileron in -1F..1F)
        require(rudder.isFinite() && rudder in -1F..1F)
        require(airbrake.isFinite() && airbrake in 0F..1F)
        require(throttle.isFinite() && throttle in 0F..1F)
    }
}
