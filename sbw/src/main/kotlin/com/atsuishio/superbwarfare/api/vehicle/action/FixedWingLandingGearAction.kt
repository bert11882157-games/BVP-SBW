package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightActionIds

/** A single accepted press commits one gear transition without owning or replacing another action. */
class FixedWingLandingGearAction : VehicleAction() {
    override val requiresUnlockedControls = true
    private var handled = false

    override fun handleInput(context: VehicleActionContext, held: Boolean): VehicleActionUpdate {
        if (held && !handled) {
            handled = true
            val operator = context.operator()
            if (operator != null && !operator.isSpectator && !operator.isRemoved &&
                !context.vehicle.isRemoved && context.vehicle.getNthEntity(0) === operator
            ) context.vehicle.requestFixedWingLandingGearToggle(operator)
        }
        return VehicleActionUpdate.COMPLETE_COMMIT
    }

    override fun tick(context: VehicleActionContext) = VehicleActionUpdate.COMPLETE_COMMIT

    override fun state(context: VehicleActionContext) = VehicleActionState(VehicleFlightActionIds.LANDING_GEAR)
}
