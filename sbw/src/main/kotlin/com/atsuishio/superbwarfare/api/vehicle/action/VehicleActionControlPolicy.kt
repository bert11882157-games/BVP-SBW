package com.atsuishio.superbwarfare.api.vehicle.action

/**
 * Vehicle-wide permissions composed across every active server action. A denied permission wins,
 * so independent addon actions cannot accidentally bypass one another's lock.
 */
data class VehicleActionControlPolicy(
    val allowsMovement: Boolean = true,
    val allowsFire: Boolean = true,
) {
    fun combinedWith(other: VehicleActionControlPolicy) = VehicleActionControlPolicy(
        allowsMovement && other.allowsMovement,
        allowsFire && other.allowsFire,
    )

    companion object {
        @JvmField
        val ALLOW_ALL = VehicleActionControlPolicy()

        @JvmField
        val BLOCK_MOVEMENT_AND_FIRE = VehicleActionControlPolicy(false, false)
    }
}
