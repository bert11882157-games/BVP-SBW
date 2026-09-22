package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * Opt-in provider for a fixed-wing/jet strategy.  It is intentionally separate from the
 * helicopter provider contract so existing aircraft and helicopters are not silently rerouted.
 * Implementations should return one stable strategy instance for an entity lifetime; the shared
 * controller uses identity to preserve throttle, momentum, and stall state across ticks.
 */
fun interface FixedWingFlightStrategyProvider {
    fun createFixedWingFlightStrategy(vehicle: VehicleEntity): FixedWingFlightStrategy?
}
