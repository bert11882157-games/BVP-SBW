package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.VehicleGeometricZeroWireStatus
import com.atsuishio.superbwarfare.network.message.receive.VehicleGeometricZeroDistanceAckMessage
import com.atsuishio.superbwarfare.network.message.send.SetVehicleGeometricZeroDistanceMessage
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.UUID

/**
 * Client-only edge state for geometric zero. Non-HasFCS vehicles retain the old 50/100/200
 * selection, while HasFCS G requests use the same ID66 edge and expose only the scalar server
 * result through [activeFcsState].
 */
@OnlyIn(Dist.CLIENT)
object VehicleGeometricZeroDistanceClient {
    const val DEFAULT_DISTANCE_BLOCKS = 100
    const val MAX_FCS_ZERO_RANGE_BLOCKS = 4_096.0
    const val MAX_FCS_ZERO_ELEVATION_DEGREES = 180.0

    /** Immutable, exact-context HUD/diagnostic view of the latest HasFCS result. */
    data class ActiveFcsView(
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val dimension: ResourceLocation,
        val operatorUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val requestSequence: Long,
        val revision: Long,
        val contextEpoch: Long,
        val authoritativeServerTick: Long,
        val status: VehicleGeometricZeroWireStatus,
        val measuredRangeBlocks: Double?,
        val elevationOffsetDegrees: Double?,
        val sourceTransformSequence: Int,
        val sourceTransformServerTick: Long,
    )

    private data class Context(
        val level: Level,
        val dimension: ResourceLocation,
        val playerUuid: UUID,
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
        val profile: VehicleAimProfile,
        val hasFcs: Boolean,
    )

    private data class State(
        var context: Context,
        var authoritativeDistance: Int = DEFAULT_DISTANCE_BLOCKS,
        var pendingDistance: Int? = null,
        var fcsState: ActiveFcsView? = null,
        var pendingFcsSequence: Long? = null,
        var lastSentSequence: Long = 0L,
        var lastAcknowledgedSequence: Long = 0L,
    )

    private var observedConnection: ClientPacketListener? = null
    private var nextSequence = 0L
    private var state: State? = null

    /**
     * Sends one authenticated G edge. HasFCS uses a null distance discriminator; legacy vehicles
     * continue to cycle 50 -> 100 -> 200 -> 50 without creating an FCS state.
     */
    @JvmStatic
    fun requestCycle(player: Player): Boolean {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection ?: return false
        if (minecraft.player !== player) return false
        val context = contextOf(player) ?: run {
            resetContext()
            return false
        }
        observeConnection(connection)
        val current = ensureState(context) ?: return false
        if (nextSequence == Long.MAX_VALUE) return false
        nextSequence += 1L
        current.lastSentSequence = nextSequence

        val requested = if (context.hasFcs) {
            current.pendingDistance = null
            current.fcsState = null
            current.pendingFcsSequence = nextSequence
            null
        } else {
            current.pendingFcsSequence = null
            val value = nextDistance(current.pendingDistance ?: current.authoritativeDistance)
            current.pendingDistance = value
            value
        }

        sendPacketToServer(
            SetVehicleGeometricZeroDistanceMessage(
                context.vehicleId,
                context.vehicleUuid,
                context.dimension,
                context.seatIndex,
                context.selectedWeaponIndex,
                requested,
                nextSequence,
            ),
        )
        return true
    }

    /** Returns the accepted value, or the newest local pending value, for legacy vehicles only. */
    @JvmStatic
    fun activeDistance(player: Player): Int? {
        val context = currentContext(player) ?: return null
        val current = ensureState(context) ?: return null
        if (context.hasFcs) return null
        return current.pendingDistance ?: current.authoritativeDistance
    }

    /**
     * Returns the latest exact-context HasFCS result for HUD/diagnostic consumers. INACTIVE and
     * every lifecycle/context mismatch fail closed; terminal statuses may still expose a measured
     * scalar range, but never an elevation authority through this client seam.
     */
    @JvmStatic
    fun activeFcsState(player: Player): ActiveFcsView? {
        val context = currentContext(player) ?: return null
        if (!context.hasFcs) return null
        val current = ensureState(context) ?: return null
        val view = current.fcsState ?: return null
        if (view.status == VehicleGeometricZeroWireStatus.INACTIVE ||
            current.pendingFcsSequence?.let { view.requestSequence < it } == true ||
            !matches(view, context, current)
        ) return null
        return view
    }

    /** Applies only a monotonic acknowledgement for the exact current context. */
    @JvmStatic
    fun accept(message: VehicleGeometricZeroDistanceAckMessage) {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection ?: run {
            clear()
            return
        }
        observeConnection(connection)
        val player = minecraft.player ?: run {
            resetContext()
            return
        }
        val context = currentContext(player) ?: return
        val current = ensureState(context) ?: return
        if (!messageContextMatches(message, context) ||
            message.operatorUuid != context.playerUuid ||
            message.requestSequence <= current.lastAcknowledgedSequence ||
            message.requestSequence > current.lastSentSequence ||
            !wireSafe(message)
        ) return

        when (message.status) {
            VehicleGeometricZeroWireStatus.MANUAL -> {
                if (context.hasFcs || message.distanceBlocks == null) return
                current.authoritativeDistance = message.distanceBlocks
                current.pendingFcsSequence = null
                if (message.requestSequence == current.lastSentSequence) current.pendingDistance = null
            }
            VehicleGeometricZeroWireStatus.INACTIVE,
            VehicleGeometricZeroWireStatus.UNSUPPORTED,
            VehicleGeometricZeroWireStatus.NO_BLOCK_HIT,
            VehicleGeometricZeroWireStatus.NO_SOLUTION,
            VehicleGeometricZeroWireStatus.SOLUTION,
            -> {
                if (!context.hasFcs || message.distanceBlocks != null) return
                val pending = current.pendingFcsSequence
                if (pending != null && message.requestSequence < pending) return
                current.fcsState = if (message.status == VehicleGeometricZeroWireStatus.INACTIVE) {
                    null
                } else {
                    ActiveFcsView(
                        message.vehicleId,
                        message.vehicleUuid,
                        message.dimension,
                        message.operatorUuid,
                        message.seatIndex,
                        message.selectedWeaponIndex,
                        message.requestSequence,
                        message.revision,
                        message.contextEpoch,
                        message.authoritativeServerTick,
                        message.status,
                        message.measuredRangeBlocks,
                        message.elevationOffsetDegrees,
                        message.sourceTransformSequence,
                        message.sourceTransformServerTick,
                    )
                }
                if (pending == message.requestSequence) current.pendingFcsSequence = null
            }
        }
        current.lastAcknowledgedSequence = message.requestSequence
    }

    /** Clears connection/context state on explicit client lifecycle transitions. */
    @JvmStatic
    fun clear() {
        observedConnection = null
        nextSequence = 0L
        state = null
    }

    /** Drops the current vehicle result while retaining the connection-local replay sequence. */
    private fun resetContext() {
        state = null
    }

    private fun currentContext(player: Player): Context? {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection ?: run {
            clear()
            return null
        }
        if (minecraft.player !== player) {
            resetContext()
            return null
        }
        observeConnection(connection)
        return contextOf(player) ?: run {
            resetContext()
            null
        }
    }

    private fun contextOf(player: Player): Context? {
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        if (!player.isAlive || player.isSpectator || player.isRemoved || vehicle.isRemoved || vehicle.isWreck ||
            vehicle.level() !== player.level() || vehicle.resolveVehicleFlightStrategy() != null
        ) return null
        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0 || vehicle.getNthEntity(seatIndex) !== player ||
            vehicle.turretControllerIndex != seatIndex ||
            vehicle.getNthEntity(vehicle.turretControllerIndex) !== player
        ) return null
        val selectedWeaponIndex = vehicle.getSelectedWeapon(seatIndex)
        val profile = vehicle.getVehicleAimPresentationProfile(player, VehicleAimChannel.TURRET)
            ?.takeIf { it.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM } ?: return null
        return Context(
            vehicle.level(),
            vehicle.level().dimension().location(),
            player.uuid,
            vehicle.id,
            vehicle.uuid,
            seatIndex,
            selectedWeaponIndex,
            profile,
            vehicle.computed().hasFCS,
        )
    }

    private fun observeConnection(connection: ClientPacketListener) {
        if (observedConnection !== connection) {
            observedConnection = connection
            nextSequence = 0L
            state = null
        }
    }

    private fun ensureState(context: Context): State? {
        val previous = state
        if (previous == null) return freshState(context).also { state = it }
        if (previous.context.level !== context.level ||
            previous.context.dimension != context.dimension ||
            previous.context.playerUuid != context.playerUuid ||
            previous.context.vehicleId != context.vehicleId ||
            previous.context.vehicleUuid != context.vehicleUuid ||
            previous.context.seatIndex != context.seatIndex
        ) return freshState(context).also { state = it }
        if (!compatible(previous.context.profile, context.profile) ||
            previous.context.hasFcs != context.hasFcs
        ) return freshState(context).also { state = it }

        if (previous.context.selectedWeaponIndex != context.selectedWeaponIndex) {
            // A selected-weapon edge is a new result context even when the physical profile is
            // compatible. Retain only the legacy accepted distance; never let an old ACK (or
            // FCS scalar) cross an ABA transition.
            previous.lastSentSequence = maxOf(previous.lastSentSequence, nextSequence)
            previous.lastAcknowledgedSequence = maxOf(previous.lastAcknowledgedSequence, nextSequence)
            previous.pendingDistance = null
            previous.fcsState = null
            previous.pendingFcsSequence = null
        }
        previous.context = context
        return previous
    }

    /** Starts a context stream above every sequence retired by a prior vehicle/context. */
    private fun freshState(context: Context): State = State(
        context = context,
        lastSentSequence = nextSequence,
        lastAcknowledgedSequence = nextSequence,
    )

    private fun messageContextMatches(
        message: VehicleGeometricZeroDistanceAckMessage,
        context: Context,
    ): Boolean = message.vehicleId == context.vehicleId &&
        message.vehicleUuid == context.vehicleUuid &&
        message.dimension == context.dimension &&
        message.seatIndex == context.seatIndex &&
        message.selectedWeaponIndex == context.selectedWeaponIndex

    private fun matches(message: ActiveFcsView, context: Context, current: State): Boolean =
        message.vehicleId == context.vehicleId && message.vehicleUuid == context.vehicleUuid &&
            message.dimension == context.dimension && message.operatorUuid == context.playerUuid &&
            message.seatIndex == context.seatIndex &&
            message.selectedWeaponIndex == context.selectedWeaponIndex &&
            message.requestSequence <= current.lastSentSequence &&
            message.requestSequence == current.lastAcknowledgedSequence

    private fun wireSafe(message: VehicleGeometricZeroDistanceAckMessage): Boolean {
        if (message.requestSequence <= 0L || message.revision < 0L || message.contextEpoch < 0L ||
            message.authoritativeServerTick < 0L || message.sourceTransformServerTick < -1L
        ) return false
        if (message.distanceBlocks != null &&
            !VehicleAimProfile.isSupportedGeometricZeroDistance(message.distanceBlocks)
        ) return false
        val range = message.measuredRangeBlocks
        if (range != null && (!range.isFinite() || range < 0.0 || range > MAX_FCS_ZERO_RANGE_BLOCKS)) {
            return false
        }
        val elevation = message.elevationOffsetDegrees
        if (elevation != null &&
            (!elevation.isFinite() || kotlin.math.abs(elevation) > MAX_FCS_ZERO_ELEVATION_DEGREES)
        ) return false
        return when (message.status) {
            VehicleGeometricZeroWireStatus.MANUAL ->
                message.distanceBlocks != null && message.measuredRangeBlocks == null &&
                    message.elevationOffsetDegrees == null && message.sourceTransformSequence == -1 &&
                    message.sourceTransformServerTick == -1L
            VehicleGeometricZeroWireStatus.INACTIVE,
            VehicleGeometricZeroWireStatus.NO_BLOCK_HIT,
            -> message.distanceBlocks == null && range == null && elevation == null
            VehicleGeometricZeroWireStatus.UNSUPPORTED ->
                message.distanceBlocks == null && elevation == null
            VehicleGeometricZeroWireStatus.NO_SOLUTION ->
                message.distanceBlocks == null && range != null && elevation == null
            VehicleGeometricZeroWireStatus.SOLUTION ->
                message.distanceBlocks == null && range != null && elevation != null
        }
    }

    private fun compatible(previous: VehicleAimProfile, selected: VehicleAimProfile): Boolean =
        previous.channel == selected.channel &&
            previous.yawRateDegreesPerSecond == selected.yawRateDegreesPerSecond &&
            previous.pitchRateDegreesPerSecond == selected.pitchRateDegreesPerSecond &&
            previous.minYaw == selected.minYaw && previous.maxYaw == selected.maxYaw &&
            previous.minPitch == selected.minPitch && previous.maxPitch == selected.maxPitch &&
            previous.softYawLimitDegrees == selected.softYawLimitDegrees &&
            previous.softPitchLimitDegrees == selected.softPitchLimitDegrees &&
            previous.defaultMode == selected.defaultMode &&
            previous.snapToNeutralWhenInactive == selected.snapToNeutralWhenInactive &&
            previous.neutralYaw == selected.neutralYaw && previous.neutralPitch == selected.neutralPitch &&
            previous.geometricZeroDistanceBlocks == selected.geometricZeroDistanceBlocks

    private fun nextDistance(current: Int): Int = when (current) {
        50 -> DEFAULT_DISTANCE_BLOCKS
        DEFAULT_DISTANCE_BLOCKS -> 200
        else -> 50
    }
}
