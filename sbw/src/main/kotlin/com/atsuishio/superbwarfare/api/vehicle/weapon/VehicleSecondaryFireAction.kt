package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.vehicle.action.VehicleAction
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionContext
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionState
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionStopReason
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionUpdate
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * Server-owned held secondary trigger.  It never changes the selected weapon; the scheduler
 * resolves the current secondary slot and applies its normal cadence/ammo/acceptance path.
 */
class VehicleSecondaryFireAction(private val vehicle: VehicleEntity) : VehicleAction() {
    private var held = false
    private var operatorId: java.util.UUID? = null
    private var seatIndex: Int = -1
    private var contextToken: String? = null

    override fun handleInput(context: VehicleActionContext, held: Boolean): VehicleActionUpdate {
        if (held && !bindOrValidate(context)) {
            this.held = false
            return VehicleActionUpdate.COMPLETE_ROLLBACK
        }
        this.held = held
        update(context, held)
        return if (held) VehicleActionUpdate.CONTINUE else VehicleActionUpdate.COMPLETE_COMMIT
    }

    override fun tick(context: VehicleActionContext): VehicleActionUpdate {
        val operator = context.operator() ?: return VehicleActionUpdate.COMPLETE_ROLLBACK
        if (!held) return VehicleActionUpdate.COMPLETE_COMMIT
        if (operator.uuid != operatorId ||
            !vehicle.isSecondaryWeaponContextValid(operator, seatIndex, contextToken ?: return VehicleActionUpdate.COMPLETE_ROLLBACK)
        ) {
            vehicle.updateSecondaryWeaponTrigger(operator, false)
            return VehicleActionUpdate.COMPLETE_ROLLBACK
        }
        vehicle.updateSecondaryWeaponTrigger(operator, true)
        return VehicleActionUpdate.CONTINUE
    }

    override fun state(context: VehicleActionContext): VehicleActionState =
        VehicleActionState(VehicleWeaponActionIds.FIRE_SECONDARY, phaseTicks = if (held) 1 else 0)

    override fun onStopped(context: VehicleActionContext, reason: VehicleActionStopReason) {
        update(context, false)
        held = false
        operatorId = null
        seatIndex = -1
        contextToken = null
    }

    private fun update(context: VehicleActionContext, value: Boolean) {
        context.operator()?.let { vehicle.updateSecondaryWeaponTrigger(it, value) }
    }

    private fun bindOrValidate(context: VehicleActionContext): Boolean {
        val operator = context.operator() ?: return false
        val currentSeat = vehicle.getSeatIndex(operator)
        if (currentSeat < 0 || !vehicle.hasSecondaryWeapon(currentSeat)) return false
        val currentToken = vehicle.secondaryWeaponContextToken(currentSeat)
        if (operatorId == null) {
            operatorId = operator.uuid
            seatIndex = currentSeat
            contextToken = currentToken
            return true
        }
        return operatorId == operator.uuid &&
            seatIndex == currentSeat &&
            contextToken == currentToken &&
            vehicle.isSecondaryWeaponContextValid(operator, seatIndex, currentToken)
    }
}
