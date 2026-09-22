package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleFcsZeroState
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleFcsZeroStatus
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.VehicleGeometricZeroDistanceAckMessage
import com.atsuishio.superbwarfare.network.message.send.SetVehicleGeometricZeroDistanceMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import java.util.WeakHashMap

/** Server admission and controller-only acknowledgement for geometric-zero selection. */
object VehicleGeometricZeroDistanceTransport {
    const val MAX_SEAT_INDEX = 2_047
    const val MAX_FCS_ZERO_RANGE_BLOCKS = 4_096.0
    const val MAX_FCS_ZERO_ELEVATION_DEGREES = 180.0

    private val lastAcceptedSequence = WeakHashMap<Connection, Long>()
    private val pending = WeakHashMap<Connection, Long>()

    /**
     * Revalidates the complete live context before invoking the vehicle's zero-selection API once.
     * Rejected requests never advance the connection watermark or emit an acknowledgement.
     */
    @Synchronized
    @JvmStatic
    fun admit(player: ServerPlayer, message: SetVehicleGeometricZeroDistanceMessage): Boolean {
        val requestedDistance = message.requestedDistanceBlocks
        if (!player.isAlive || player.isSpectator || player.isRemoved) return false
        if (message.vehicleId < 0 || message.seatIndex !in 0..MAX_SEAT_INDEX ||
            message.selectedWeaponIndex < 0 || message.requestSequence <= 0L ||
            (requestedDistance != null &&
                    !VehicleAimProfile.isSupportedGeometricZeroDistance(requestedDistance))
        ) return false

        val vehicle = player.vehicle as? VehicleEntity ?: return false
        if (vehicle.id != message.vehicleId || vehicle.uuid != message.vehicleUuid ||
            vehicle.level() !== player.level() ||
            vehicle.level().dimension().location() != message.dimension ||
            vehicle.isRemoved || vehicle.isWreck
        ) return false
        if (vehicle.getSeatIndex(player) != message.seatIndex ||
            vehicle.getNthEntity(message.seatIndex) !== player ||
            vehicle.turretControllerIndex != message.seatIndex ||
            vehicle.getNthEntity(vehicle.turretControllerIndex) !== player
        ) return false

        val selectedWeaponIndex = vehicle.getSelectedWeapon(message.seatIndex)
        if (selectedWeaponIndex != message.selectedWeaponIndex ||
            vehicle.resolveVehicleFlightStrategy() != null
        ) return false
        val profile = vehicle.resolveVehicleAimProfile(message.seatIndex, selectedWeaponIndex)
            ?.takeIf {
                it.channel == VehicleAimChannel.TURRET &&
                    it.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM
            } ?: return false

        val hasFcs = VehicleLaserRangefinder.enabled(vehicle, message.seatIndex, selectedWeaponIndex)
        // ID66 is a tagged union without a second discriminator: null is reserved for HasFCS G,
        // while the three integer values remain the legacy non-HasFCS manual cycle.
        if ((hasFcs && requestedDistance != null) || (!hasFcs && requestedDistance == null)) return false

        val connection = player.connection.connection
        val previous = lastAcceptedSequence[connection]
        if (previous != null && message.requestSequence <= previous) return false
        if (pending.containsKey(connection)) return false

        if (hasFcs) {
            lastAcceptedSequence[connection] = message.requestSequence
            pending[connection] = message.requestSequence
            vehicle.requestVehicleFcsZeroAsync(player, message.requestSequence).whenComplete { result, error ->
                player.server.execute {
                    synchronized(this) { pending.remove(connection) }
                    if (player.connection.connection === connection) {
                        val completed = if (error == null && result != null) result else
                            VehicleFcsZeroState.inactive(vehicle.uuid, 0L, 0L,
                                vehicle.level().gameTime, message.requestSequence)
                        fcsAcknowledgement(player, vehicle, message, completed)?.let { sendPacketTo(player, it) }
                    }
                }
            }
            return true
        }
        val distance = requestedDistance ?: return false
        if (!vehicle.setVehicleGeometricZeroDistance(player, distance)) return false
        val acknowledgement = manualAcknowledgement(player, vehicle, message, distance)
        lastAcceptedSequence[connection] = message.requestSequence
        sendPacketTo(player, acknowledgement)
        return true
    }

    private fun manualAcknowledgement(
        player: ServerPlayer,
        vehicle: VehicleEntity,
        message: SetVehicleGeometricZeroDistanceMessage,
        distance: Int,
    ) = VehicleGeometricZeroDistanceAckMessage(
        vehicle.id,
        vehicle.uuid,
        vehicle.level().dimension().location(),
        player.uuid,
        message.seatIndex,
        message.selectedWeaponIndex,
        message.requestSequence,
        0L,
        0L,
        vehicle.level().gameTime,
        VehicleGeometricZeroWireStatus.MANUAL,
        distance,
        null,
        null,
        -1,
        -1L,
    )

    private fun fcsAcknowledgement(
        player: ServerPlayer,
        vehicle: VehicleEntity,
        message: SetVehicleGeometricZeroDistanceMessage,
        state: VehicleFcsZeroState,
    ): VehicleGeometricZeroDistanceAckMessage? {
        if (state.vehicleUuid != vehicle.uuid || state.requestSequence != message.requestSequence ||
            (state.operatorUuid != null && state.operatorUuid != player.uuid) ||
            (state.seatIndex >= 0 && state.seatIndex != message.seatIndex) ||
            (state.selectedWeaponIndex >= 0 && state.selectedWeaponIndex != message.selectedWeaponIndex) ||
            !wireSafe(state)
        ) return null

        // The aim controller uses an unbound INACTIVE value when clearing context. The transport has
        // already revalidated this request, so bind that terminal result to the exact claimed
        // context instead of exposing a wildcard client state.
        val operatorUuid = state.operatorUuid ?: player.uuid
        val seatIndex = if (state.seatIndex >= 0) state.seatIndex else message.seatIndex
        val selectedWeaponIndex = if (state.selectedWeaponIndex >= 0) {
            state.selectedWeaponIndex
        } else {
            message.selectedWeaponIndex
        }
        return VehicleGeometricZeroDistanceAckMessage(
            vehicle.id,
            vehicle.uuid,
            vehicle.level().dimension().location(),
            operatorUuid,
            seatIndex,
            selectedWeaponIndex,
            state.requestSequence,
            state.revision,
            state.contextEpoch,
            state.authoritativeServerTick,
            wireStatus(state.status),
            null,
            state.measuredRangeBlocks,
            state.elevationOffsetDegrees,
            state.sourceTransformSequence,
            state.sourceTransformServerTick,
        )
    }

    private fun wireStatus(status: VehicleFcsZeroStatus): VehicleGeometricZeroWireStatus = when (status) {
        VehicleFcsZeroStatus.INACTIVE -> VehicleGeometricZeroWireStatus.INACTIVE
        VehicleFcsZeroStatus.UNSUPPORTED -> VehicleGeometricZeroWireStatus.UNSUPPORTED
        VehicleFcsZeroStatus.NO_BLOCK_HIT -> VehicleGeometricZeroWireStatus.NO_BLOCK_HIT
        VehicleFcsZeroStatus.NO_SOLUTION -> VehicleGeometricZeroWireStatus.NO_SOLUTION
        VehicleFcsZeroStatus.SOLUTION -> VehicleGeometricZeroWireStatus.SOLUTION
    }

    private fun wireSafe(state: VehicleFcsZeroState): Boolean {
        if (state.requestSequence <= 0L || state.revision < 0L || state.contextEpoch < 0L ||
            state.authoritativeServerTick < 0L || state.sourceTransformServerTick < -1L
        ) return false
        val range = state.measuredRangeBlocks
        if (range != null && (!range.isFinite() || range < 0.0 || range > MAX_FCS_ZERO_RANGE_BLOCKS)) {
            return false
        }
        val elevation = state.elevationOffsetDegrees
        if (elevation != null &&
            (!elevation.isFinite() || kotlin.math.abs(elevation) > MAX_FCS_ZERO_ELEVATION_DEGREES)
        ) return false
        return when (state.status) {
            VehicleFcsZeroStatus.INACTIVE,
            VehicleFcsZeroStatus.NO_BLOCK_HIT,
            -> range == null && elevation == null
            VehicleFcsZeroStatus.UNSUPPORTED -> elevation == null
            VehicleFcsZeroStatus.NO_SOLUTION -> range != null && elevation == null
            VehicleFcsZeroStatus.SOLUTION -> range != null && elevation != null
        }
    }
}
