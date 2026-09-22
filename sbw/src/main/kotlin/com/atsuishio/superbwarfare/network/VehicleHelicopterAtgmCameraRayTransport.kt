package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidance
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.send.VehicleHelicopterAtgmCameraRayMessage
import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap

/**
 * Admission and server-side retention for every vehicle ATGM camera ray.
 * The historical class/packet name is retained to avoid a needless wire-format change.
 *
 * The packet is a presentation input only. This stream is keyed by the operator, vehicle and
 * seat, not by the currently selected weapon, so an already-launched missile can keep following
 * its immutable launch owner while that operator changes secondary selection. The server checks
 * that the occupied seat has an authored ATGM-capable weapon pair; weapon identity comes only
 * from the immutable launch context.
 */
data class VehicleHelicopterAtgmCameraRayState(
    val connection: Connection,
    val controllerUuid: UUID,
    val vehicleId: Int,
    val vehicleUuid: UUID,
    val dimension: net.minecraft.resources.ResourceLocation,
    val seatIndex: Int,
    val contextEpoch: Long,
    val sequence: Long,
    val clientTick: Long,
    val receivedServerTick: Long,
    val origin: Vec3,
    val direction: Vec3,
)

object VehicleHelicopterAtgmCameraRayTransport {
    const val MAX_CONTEXT_INDEX = 2_047
    const val MAX_ORIGIN_DISTANCE_BLOCKS = 32.0
    const val MAX_SAMPLE_AGE_TICKS = 4L
    private const val MIN_DIRECTION_LENGTH_SQUARED = 0.9025
    private const val MAX_DIRECTION_LENGTH_SQUARED = 1.1025

    private data class StreamKey(
        val playerUuid: UUID,
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val dimension: net.minecraft.resources.ResourceLocation,
        val seatIndex: Int,
    )

    private data class Admission(
        var lastSequence: Long = -1L,
        var lastClientTick: Long = -1L,
        var lastContextEpoch: Long = -1L,
        var stream: StreamKey? = null,
    )

    private val admissions = WeakHashMap<Connection, Admission>()

    /** Full semantic admission.  A false result never mutates the vehicle's retained ray. */
    @Synchronized
    @JvmStatic
    fun admit(player: ServerPlayer, message: VehicleHelicopterAtgmCameraRayMessage): Boolean {
        if (player.uuid != message.playerUuid || !player.isAlive || player.isSpectator) return false
        if (message.vehicleId < 0 || message.seatIndex !in 0..MAX_CONTEXT_INDEX ||
            message.contextEpoch < 0L || message.sequence < 0L || message.clientTick < 0L
        ) return false

        val vehicle = player.vehicle as? VehicleEntity ?: return false
        if (vehicle.id != message.vehicleId || vehicle.uuid != message.vehicleUuid ||
            vehicle.level() !== player.level() ||
            vehicle.level().dimension().location() != message.dimension ||
            vehicle.isRemoved || vehicle.isWreck
        ) return false
        if (vehicle.getSeatIndex(player) != message.seatIndex ||
            vehicle.getNthEntity(message.seatIndex) !== player ||
            !vehicle.hasAtgmCapableSeat(message.seatIndex)
        ) return false

        val origin = Vec3(message.origin.x.toDouble(), message.origin.y.toDouble(), message.origin.z.toDouble())
        val direction = Vec3(message.direction.x.toDouble(), message.direction.y.toDouble(), message.direction.z.toDouble())
        if (!origin.isFinite() || !direction.isFinite()) return false
        val directionLengthSquared = direction.lengthSqr()
        if (directionLengthSquared !in MIN_DIRECTION_LENGTH_SQUARED..MAX_DIRECTION_LENGTH_SQUARED) return false
        if (origin.distanceToSqr(vehicle.position()) > MAX_ORIGIN_DISTANCE_BLOCKS * MAX_ORIGIN_DISTANCE_BLOCKS) return false

        val connection = player.connection.connection
        val key = StreamKey(player.uuid, vehicle.id, vehicle.uuid, message.dimension, message.seatIndex)
        val admission = admissions.getOrPut(connection) { Admission() }
        if (message.sequence <= admission.lastSequence || message.clientTick <= admission.lastClientTick) return false
        if (message.contextEpoch < admission.lastContextEpoch) return false
        if (admission.stream != null && admission.stream != key && message.contextEpoch <= admission.lastContextEpoch) return false

        admission.lastSequence = message.sequence
        admission.lastClientTick = message.clientTick
        admission.lastContextEpoch = message.contextEpoch
        admission.stream = key
        vehicle.acceptHelicopterAtgmCameraRay(
            VehicleHelicopterAtgmCameraRayState(
                connection,
                player.uuid,
                vehicle.id,
                vehicle.uuid,
                message.dimension,
                message.seatIndex,
                message.contextEpoch,
                message.sequence,
                message.clientTick,
                vehicle.level().gameTime,
                origin,
                direction.normalize(),
            )
        )
        return true
    }

    /**
     * A paired ATGM seat is stream-eligible even when the primary-locked selected gun is not the
     * ATGM. Mi-24/Mi-28/Ka-50 seats commonly expose cannon/rocket as primary and ATGM as the
     * authored secondary; selection is intentionally absent from ID 64.
     */
    private fun VehicleEntity.hasAtgmCapableSeat(seatIndex: Int): Boolean {
        val seat = getSeat(seatIndex) ?: return false
        return seat.weapons().any { weaponName ->
            getGunData(weaponName)?.let { VehicleWeaponGuidance.isAtgm(it) } == true
        }
    }

    @JvmStatic
    fun isFresh(state: VehicleHelicopterAtgmCameraRayState, serverTick: Long): Boolean {
        val age = serverTick - state.receivedServerTick
        return age in 0L..MAX_SAMPLE_AGE_TICKS
    }

    private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}
