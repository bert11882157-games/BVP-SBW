package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Return a stable strategy instance to replace legacy EngineInfo integration for this vehicle. */
fun interface VehicleFlightStrategyProvider {
    fun createVehicleFlightStrategy(vehicle: VehicleEntity): VehicleFlightStrategy?
}
