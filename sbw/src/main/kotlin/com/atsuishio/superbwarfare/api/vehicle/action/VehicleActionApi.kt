package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import java.util.UUID

/** Result of one server-authoritative action transition. */
enum class VehicleActionUpdate {
    /** Keep the session and its current transaction journal active. */
    CONTINUE,

    /** End the session and keep every journaled change. */
    COMPLETE_COMMIT,

    /** End the session and subtract only the gains recorded by its current transaction. */
    COMPLETE_ROLLBACK,
}

enum class VehicleActionStopReason {
    COMPLETED,
    INPUT_CANCELLED,
    ACTION_ROLLBACK,
    OPERATOR_LEFT,
    VEHICLE_WRECKED,
    VEHICLE_REMOVED,
}

/** Addon-owned semantic state copied into the native synchronized snapshot. */
data class VehicleActionState @JvmOverloads constructor(
    val phaseId: ResourceLocation,
    val phaseTicks: Int = 0,
    val targetId: ResourceLocation? = null,
)

/** Stable server context for one action session. */
class VehicleActionContext internal constructor(
    val vehicle: VehicleEntity,
    val operatorId: UUID,
    val journal: VehicleActionTransactionJournal,
) {
    fun operator(): Player? = vehicle.passengers
        .firstOrNull { it is Player && it.uuid == operatorId } as? Player
}

/**
 * One addon-defined action instance. SBW owns its operator, input sequencing, transaction,
 * cancellation, control locks, persistence rollback, and synchronized snapshot lifecycle.
 */
abstract class VehicleAction {
    abstract fun handleInput(context: VehicleActionContext, held: Boolean): VehicleActionUpdate

    abstract fun tick(context: VehicleActionContext): VehicleActionUpdate

    abstract fun state(context: VehicleActionContext): VehicleActionState

    open fun controlPolicy(context: VehicleActionContext): VehicleActionControlPolicy =
        VehicleActionControlPolicy.ALLOW_ALL

    open fun onStopped(context: VehicleActionContext, reason: VehicleActionStopReason) = Unit
}

fun interface VehicleActionFactory {
    /** Return null when this action ID is not supported by the concrete vehicle. */
    fun create(vehicle: VehicleEntity): VehicleAction?
}
