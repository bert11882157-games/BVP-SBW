package com.atsuishio.superbwarfare.network

import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Exact runtime identity without retaining a connection, player, entity or world. */
internal class FixedWingPilotInputIdentity<C : Any, P : Any, V : Any, L : Any>(
    connection: C, operator: P, vehicle: V, level: L,
) {
    val connection = WeakReference(connection)
    val operator = WeakReference(operator)
    val vehicle = WeakReference(vehicle)
    val level = WeakReference(level)

    fun matches(connection: C, operator: P, vehicle: V, level: L): Boolean =
        this.connection.get() === connection && this.operator.get() === operator &&
            this.vehicle.get() === vehicle && this.level.get() === level
}

/** An identity-bound server input capability. Its fixed deadline cannot be renewed. */
class FixedWingPilotInputReservation internal constructor(
    val context: FixedWingPilotIntentKey,
    val runToken: UUID,
    val controlEpoch: Long,
    val startedServerTick: Long,
    val durationTicks: Int,
    private val startedNanos: Long,
) {
    val expiresAtServerTick: Long = startedServerTick + durationTicks

    internal fun isCurrent(
        context: FixedWingPilotIntentKey, epoch: Long, serverTick: Long, nowNanos: Long,
    ): Boolean {
        val elapsedNanos = nowNanos - startedNanos
        return this.context == context && controlEpoch == epoch &&
            serverTick >= startedServerTick && serverTick < expiresAtServerTick &&
            elapsedNanos >= 0L && elapsedNanos < durationTicks * NANOS_PER_TICK
    }

    companion object {
        const val MAX_DURATION_TICKS = 5_400
        private const val NANOS_PER_TICK = 50_000_000L

        internal fun validRequest(runToken: UUID, epoch: Long, serverTick: Long, durationTicks: Int) =
            runToken != UUID(0L, 0L) && epoch > 0L && serverTick >= 0L &&
                durationTicks in 1..MAX_DURATION_TICKS &&
                serverTick <= Long.MAX_VALUE - durationTicks
    }
}

/** Pure admission policy; the server adapter supplies observed, not requested, properties. */
internal object FixedWingPilotInputDiagnostics {
    fun permitted(
        enabled: Boolean, dedicated: Boolean, authenticated: Boolean,
        bindAddress: String, peerAddress: SocketAddress?, playerCount: Int,
        solePlayer: Boolean, operator: Boolean, operatorName: String, operatorUuid: UUID,
    ): Boolean {
        val peer = peerAddress as? InetSocketAddress ?: return false
        return enabled && dedicated && !authenticated &&
            (bindAddress == "127.0.0.1" || bindAddress == "::1") &&
            peer.address?.isLoopbackAddress == true &&
            playerCount == 1 && solePlayer && operator && operatorName.isNotBlank() &&
            operatorUuid == UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + operatorName).toByteArray(StandardCharsets.UTF_8))
    }
}
