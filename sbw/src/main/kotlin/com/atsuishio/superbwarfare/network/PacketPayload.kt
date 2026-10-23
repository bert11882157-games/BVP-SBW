package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.Mod
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

typealias PayloadContext = Supplier<NetworkEvent.Context>

sealed class PacketPayload {
    abstract fun handleInternal(message: PacketPayload, context: PayloadContext)
    abstract fun PayloadContext.handler()
}

abstract class ServerPacketPayload : PacketPayload() {
    fun PayloadContext.sender() = get().sender as ServerPlayer

    override fun handleInternal(
        message: PacketPayload,
        context: PayloadContext,
    ) {
        val networkContext = context.get()
        val registered = NetworkPacketManifest.schemaFor(message.javaClass)
        if (registered.schema.direction != PacketDirection.PLAY_TO_SERVER) {
            NetworkTelemetry.recordReject(registered, PacketRejectReason.WRONG_DIRECTION)
            networkContext.packetHandled = true
            return
        }

        val player = networkContext.sender
        if (player == null) {
            NetworkTelemetry.recordReject(registered, PacketRejectReason.MISSING_SENDER)
            networkContext.packetHandled = true
            return
        }
        if (!NetworkPacketGuard.admit(player, registered)) {
            NetworkTelemetry.recordReject(registered, PacketRejectReason.RATE_LIMIT)
            player.connection.disconnect(Component.literal("Superb Warfare rejected an excessive packet rate."))
            networkContext.packetHandled = true
            return
        }
        if (!NetworkTelemetry.tryEnqueue(registered)) {
            player.connection.disconnect(Component.literal("Superb Warfare network work queue is overloaded; reconnect after recovery."))
            networkContext.packetHandled = true
            return
        }

        val queuedAt = System.nanoTime()
        networkContext.enqueueWork {
            val startedAt = System.nanoTime()
            try {
                with(message) { context.handler() }
            } catch (t: Throwable) {
                // enqueueWork hides a failed task inside its future; log it so a broken handler is visible
                PacketFailureLog.report("server", message, t)
            } finally {
                NetworkTelemetry.recordHandled(registered, queuedAt, startedAt)
            }
        }
        networkContext.packetHandled = true
    }
}

abstract class ClientPacketPayload : PacketPayload() {
    override fun handleInternal(message: PacketPayload, context: PayloadContext) {
        val networkContext = context.get()
        val registered = NetworkPacketManifest.schemaFor(message.javaClass)
        if (registered.schema.direction != PacketDirection.PLAY_TO_CLIENT) {
            NetworkTelemetry.recordReject(registered, PacketRejectReason.WRONG_DIRECTION)
            networkContext.packetHandled = true
            return
        }
        if (!NetworkTelemetry.tryEnqueue(registered)) {
            networkContext.networkManager.disconnect(
                Component.literal("Superb Warfare client network work queue is overloaded; reconnect after recovery."),
            )
            networkContext.packetHandled = true
            return
        }

        val queuedAt = System.nanoTime()
        networkContext.enqueueWork {
            val startedAt = System.nanoTime()
            try {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    DistExecutor.SafeRunnable { with(message) { context.handler() } }
                }
            } catch (t: Throwable) {
                // enqueueWork hides a failed task inside its future; log it so a broken handler is visible
                PacketFailureLog.report("client", message, t)
            } finally {
                NetworkTelemetry.recordHandled(registered, queuedAt, startedAt)
            }
        }
        networkContext.packetHandled = true
    }
}

/** Logs a failing packet handler at most once per message type every 10 s, so a handler that throws every tick cannot flood the log. */
private object PacketFailureLog {
    private const val INTERVAL_NANOS = 10_000_000_000L
    private val last = java.util.concurrent.ConcurrentHashMap<Class<*>, Long>()

    fun report(side: String, message: PacketPayload, failure: Throwable) {
        val now = System.nanoTime()
        val previous = last[message.javaClass]
        if (previous != null && now - previous < INTERVAL_NANOS) return
        last[message.javaClass] = now
        Mod.LOGGER.error(
            "Superb Warfare {} packet handler {} failed (repeats suppressed for 10 s)",
            side, message.javaClass.simpleName, failure
        )
    }
}
