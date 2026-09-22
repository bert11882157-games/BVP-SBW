package com.atsuishio.superbwarfare.api.vehicle.flight

/** Distinguishes the flight owners without changing the legacy or helicopter strategy paths. */
enum class VehicleFlightStrategyKind {
    LEGACY,
    HELICOPTER,
    FIXED_WING,
}
