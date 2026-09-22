package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * Opt-in complete flight owner. [tickServer] is never invoked on a client; clients receive only
 * [onClientInstrumentSnapshot] callbacks after native decoding and reconciliation.
 */
abstract class VehicleFlightStrategy {
    /** Stable owner identity; legacy and existing helicopter strategies retain their defaults. */
    open val strategyKind: VehicleFlightStrategyKind = VehicleFlightStrategyKind.LEGACY

    open fun onActivated(vehicle: VehicleEntity) = Unit

    /** Optional server-only state/attitude preparation performed before native input is sampled. */
    open fun prepareServer(vehicle: VehicleEntity) = Unit

    abstract fun tickServer(
        vehicle: VehicleEntity,
        input: VehicleFlightInputContext,
    ): VehicleFlightTickResult

    open fun onClientInstrumentSnapshot(
        vehicle: VehicleEntity,
        snapshot: VehicleFlightInstrumentSnapshot,
    ) = Unit

    open fun onDeactivated(vehicle: VehicleEntity) = Unit
}
