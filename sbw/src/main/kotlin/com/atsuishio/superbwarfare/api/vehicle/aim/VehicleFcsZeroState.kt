package com.atsuishio.superbwarfare.api.vehicle.aim

import java.util.UUID

/**
 * Immutable server result for one HasFCS G acquisition.
 *
 * The acquisition is deliberately scalar: the hit position and camera bearing are not retained
 * here.  A SOLUTION carries only the bounded vertical correction that may be applied to the
 * latest live player command; yaw, camera state, and firing state remain outside this DTO.
 */
enum class VehicleFcsZeroStatus {
    INACTIVE,
    UNSUPPORTED,
    NO_BLOCK_HIT,
    NO_SOLUTION,
    SOLUTION,
}

data class VehicleFcsZeroState(
    val vehicleUuid: UUID,
    val operatorUuid: UUID?,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val weaponName: String?,
    val requestSequence: Long,
    val revision: Long,
    val contextEpoch: Long,
    val authoritativeServerTick: Long,
    val status: VehicleFcsZeroStatus,
    val measuredRangeBlocks: Double?,
    val elevationOffsetDegrees: Double?,
    val sourceTransformSequence: Int,
    val sourceTransformServerTick: Long,
) {
    val hasSolution: Boolean
        get() = status == VehicleFcsZeroStatus.SOLUTION &&
                measuredRangeBlocks?.isFinite() == true &&
                elevationOffsetDegrees?.isFinite() == true

    companion object {
        @JvmStatic
        fun inactive(
            vehicleUuid: UUID,
            revision: Long,
            contextEpoch: Long,
            serverTick: Long,
            requestSequence: Long = -1L,
        ): VehicleFcsZeroState = VehicleFcsZeroState(
            vehicleUuid,
            null,
            -1,
            -1,
            null,
            requestSequence,
            revision,
            contextEpoch,
            serverTick,
            VehicleFcsZeroStatus.INACTIVE,
            null,
            null,
            -1,
            -1L,
        )
    }
}
