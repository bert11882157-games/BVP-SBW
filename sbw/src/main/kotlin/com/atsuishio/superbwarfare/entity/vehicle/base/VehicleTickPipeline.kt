package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData

/**
 * Owns the fixed order of one vehicle tick without owning any gameplay state.
 *
 * [VehicleEntity] retains the phase implementations while they are extracted incrementally. This
 * coordinator is deliberately small: it makes ordering reviewable and keeps the vanilla
 * [VehicleEntity.baseTick] call as the single superclass boundary in the entity facade.
 */
internal class VehicleTickPipeline(private val vehicle: VehicleEntity) {

    fun beforeVanillaLifecycle(): DefaultVehicleData =
        vehicle.tickPipelineBeforeVanillaLifecycle()

    fun afterVanillaLifecycle(computed: DefaultVehicleData) {
        vehicle.tickPipelineLifecycleAfterVanilla(computed)
        vehicle.tickPipelineTravel()
        vehicle.tickPipelineControlBeforeMovement(computed)
        vehicle.tickPipelineMovement()
        vehicle.tickPipelinePostMovement(computed)
        vehicle.tickPipelinePoseDamageAndChunk(computed)
        vehicle.tickPipelineAimAndWeapons()
        vehicle.tickPipelineRecoil()
        com.atsuishio.superbwarfare.diagnostics.EliteVehicleDiagnostics.vehicle(vehicle)
    }
}
