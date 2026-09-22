package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntentSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.FixedWingPilotIntentStateMessage
import com.atsuishio.superbwarfare.network.message.send.FixedWingPilotIntentMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.server.ServerLifecycleHooks
import java.util.UUID
import java.util.WeakHashMap

/** Server-thread transport. Its bounded values never retain a player, world, or weak-map key. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FixedWingPilotIntentTransport {
    const val SERVER_INPUT_DIAGNOSTICS_PROPERTY = "sbw.diagnostics.fixedWingServerInput"
    const val MAX_SERVER_INPUT_TICKS = FixedWingPilotInputReservation.MAX_DURATION_TICKS

    private class ReservedInput(
        val token: FixedWingPilotInputReservation,
        player: ServerPlayer,
        vehicle: VehicleEntity,
    ) {
        val identity = FixedWingPilotInputIdentity(player.connection.connection, player, vehicle, vehicle.level())
        val player get() = identity.operator
        val vehicle get() = identity.vehicle
    }

    private class Session {
        val mailbox = FixedWingPilotIntentMailbox()
        var key: FixedWingPilotIntentKey? = null
        var epoch = 0L
        var lastPublished: FixedWingPilotIntentSnapshot? = null
        var lastPublishTick = Long.MIN_VALUE
        var reservedInput: ReservedInput? = null
    }

    private val sessions = WeakHashMap<Connection, Session>()

    /**
     * Server-thread private diagnostics only. The caller must release in finally and must not
     * reserve client-input tests. A successful reservation retires normal input and starts a
     * separate Physics epoch; it never supplies or applies a fixture direction.
     */
    @JvmStatic
    fun acquireServerInput(
        player: ServerPlayer, vehicle: VehicleEntity, runToken: UUID, durationTicks: Int,
    ): FixedWingPilotInputReservation? {
        if (!player.server.isSameThread || !privateOperator(player) || pilotVehicle(player) !== vehicle ||
            !FixedWingPilotInputReservation.validRequest(runToken, 1L, vehicle.level().gameTime, durationTicks)
        ) return null
        retireInvalidReservations(player.server)
        val connection = player.connection.connection
        val session = sessions[connection] ?: Session().also { sessions[connection] = it }
        if (session.reservedInput != null) {
            if (!reservationValid(player, session, System.nanoTime())) finishReservation(session)
            return null
        }
        val strategy = vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy ?: return null
        deactivate(player, session)
        strategy.clearPilotControls()
        val state = vehicle.getFixedWingPilotIntentState(player) ?: return null
        if (state.acceptedSequence != -1L) return null
        val context = key(player, vehicle)
        val token = session.mailbox.reserve(context, runToken, state.controlEpoch, state.serverTick,
            System.nanoTime(), durationTicks) ?: return null
        session.key = context
        session.epoch = state.controlEpoch
        session.reservedInput = ReservedInput(token, player, vehicle)
        return token
    }

    /** Validity checks never extend the fixed tick/wall-clock deadline. */
    @JvmStatic
    fun isServerInputReserved(player: ServerPlayer, token: FixedWingPilotInputReservation): Boolean {
        if (!player.server.isSameThread) return false
        val session = ownedReservation(player, token) ?: return false
        val owner = session.reservedInput ?: return false
        if (owner.token !== token || owner.player.get() !== player) return false
        if (reservationValid(player, session, System.nanoTime())) return true
        finishReservation(session)
        return false
    }

    /** May be called after dismount/context loss; only the original capability can release it. */
    @JvmStatic
    fun releaseServerInput(player: ServerPlayer, token: FixedWingPilotInputReservation): Boolean {
        if (!player.server.isSameThread) return false
        val session = ownedReservation(player, token) ?: return false
        val owner = session.reservedInput ?: return false
        if (owner.token !== token || owner.player.get() !== player) return false
        finishReservation(session)
        return true
    }

    @JvmStatic
    fun admit(player: ServerPlayer, message: FixedWingPilotIntentMessage): Boolean {
        if (!player.server.isSameThread) return false
        val session = sessions[player.connection.connection] ?: return false
        if (session.reservedInput != null) {
            if (!reservationValid(player, session, System.nanoTime())) finishReservation(session)
            return false
        }
        val vehicle = pilotVehicle(player) ?: return false
        val key = key(player, vehicle)
        if (session.key != key || session.epoch != message.controlEpoch) return false
        val snapshot = vehicle.getFixedWingPilotIntentState(player) ?: return false
        if (snapshot.controlEpoch != session.epoch) return false
        return session.mailbox.offer(message, key, snapshot.controlEpoch, System.nanoTime())
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        // START retires expired input before the physical step; END catches in-tick context loss.
        retireInvalidReservations(server)
        if (event.phase != TickEvent.Phase.END) return
        for (player in server.playerList.players) {
            val connection = player.connection.connection
            val vehicle = pilotVehicle(player)
            val existing = sessions[connection]
            if (existing?.reservedInput != null) continue
            if (vehicle == null) {
                if (existing != null) deactivate(player, existing)
                continue
            }
            val session = existing ?: Session().also { sessions[connection] = it }
            var snapshot = vehicle.getFixedWingPilotIntentState(player)
            if (snapshot == null) { deactivate(player, session); continue }
            val context = key(player, vehicle)
            if (session.key != context || session.epoch != snapshot.controlEpoch) {
                deactivate(player, session)
                session.key = context
                session.epoch = snapshot.controlEpoch
            }
            val pending = session.mailbox.take()
            var centered = false
            if (pending != null && pending.vehicleId == vehicle.id &&
                pending.vehicleUuid == vehicle.uuid && pending.dimension == context.dimension &&
                pending.controlEpoch == snapshot.controlEpoch
            ) {
                centered = vehicle.acceptFixedWingPilotIntent(player, pending.controlEpoch,
                    pending.sequence, pending.worldX, pending.worldY, pending.worldZ,
                    pending.manualMask.toInt(), pending.centerAim, pending.screenRollInput,
                    pending.firstPerson, pending.inversionRequested) && pending.centerAim
                val accepted = vehicle.getFixedWingPilotIntentState(player)
                if (accepted == null) {
                    deactivate(player, session)
                    continue
                }
                snapshot = accepted
            }
            // Retransmit an unclaimed lease: its first packet may precede client mount metadata.
            if (FixedWingPilotIntentPublication.shouldPublish(session.lastPublished, snapshot,
                    session.lastPublishTick, centered)) publish(player, session, snapshot)
        }
    }

    private fun publish(player: ServerPlayer, session: Session, state: FixedWingPilotIntentSnapshot) {
        val context = session.key ?: return
        val intent = state.intent
        val packet = FixedWingPilotIntentStateMessage(context.vehicleId, context.vehicleUuid,
            context.dimension, context.operatorUuid, state.controlEpoch, state.serverTick, true,
            FixedWingPilotIntentWireValue(state.acceptedSequence, intent.directionX, intent.directionY,
                intent.directionZ, intent.manualMask.toByte(), intent.screenRollInput, intent.firstPerson,
                intent.inversionRequested))
        if (!packet.valid()) return
        sendPacketTo(player, packet)
        session.lastPublished = state
        session.lastPublishTick = state.serverTick
    }

    private fun deactivate(player: ServerPlayer, session: Session) {
        val context = session.key
        if (context != null && session.epoch > 0L) {
            sendPacketTo(player, FixedWingPilotIntentStateMessage(context.vehicleId, context.vehicleUuid,
                context.dimension, context.operatorUuid, session.epoch,
                maxOf(player.level().gameTime, session.lastPublished?.serverTick ?: 0L), false, null))
        }
        session.key = null
        session.epoch = 0L
        session.lastPublished = null
        session.lastPublishTick = Long.MIN_VALUE
        session.mailbox.discardPending()
    }

    private fun privateOperator(player: ServerPlayer): Boolean {
        val server = player.server
        return FixedWingPilotInputDiagnostics.permitted(
            java.lang.Boolean.getBoolean(SERVER_INPUT_DIAGNOSTICS_PROPERTY),
            server.isDedicatedServer, server.usesAuthentication(), server.localIp,
            player.connection.connection.remoteAddress, server.playerCount,
            server.playerList.players.singleOrNull() === player, player.hasPermissions(2),
            player.gameProfile.name, player.uuid)
    }

    private fun reservationValid(player: ServerPlayer, session: Session, nowNanos: Long): Boolean {
        val owner = session.reservedInput ?: return false
        val vehicle = owner.vehicle.get() ?: return false
        if (!owner.identity.matches(player.connection.connection, player, vehicle, vehicle.level()) ||
            session.mailbox.reservation !== owner.token || !privateOperator(player) ||
            pilotVehicle(player) !== vehicle
        ) return false
        val snapshot = vehicle.getFixedWingPilotIntentState(player) ?: return false
        return owner.token.isCurrent(key(player, vehicle), snapshot.controlEpoch,
            vehicle.level().gameTime, nowNanos)
    }

    private fun ownedReservation(player: ServerPlayer, token: FixedWingPilotInputReservation): Session? =
        sessions.values.firstOrNull {
            it.reservedInput?.let { owner -> owner.token === token && owner.player.get() === player } == true
        }

    private fun retireInvalidReservations(server: net.minecraft.server.MinecraftServer) {
        for (session in sessions.values) {
            val owner = session.reservedInput ?: continue
            val player = owner.player.get()
            if (player == null || player.server !== server ||
                !reservationValid(player, session, System.nanoTime())) finishReservation(session)
        }
    }

    private fun finishReservation(session: Session) {
        val owner = session.reservedInput ?: return
        session.reservedInput = null
        try {
            val vehicle = owner.vehicle.get()
            val occupant = vehicle?.getNthEntity(0)
            // Never clear a different pilot's newly acquired controls after a topology change.
            if (vehicle != null &&
                (occupant == null || occupant.uuid == owner.token.context.operatorUuid)) {
                (vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy)?.clearPilotControls()
            }
        } finally {
            session.mailbox.release(owner.token)
            session.key = null
            session.epoch = 0L
            session.lastPublished = null
            session.lastPublishTick = Long.MIN_VALUE
        }
        // The next eligible normal END publishes a fresh -1 bootstrap, never a fixture ACK.
    }

    private fun pilotVehicle(player: ServerPlayer): VehicleEntity? {
        if (!player.isAlive || player.isRemoved || player.isSpectator ||
            player.connection.player !== player || !player.connection.connection.isConnected
        ) return null
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        return vehicle.takeIf {
            !it.isRemoved && !it.isWreck && it.level() === player.level() &&
                it.getSeatIndex(player) == 0 && it.getNthEntity(0) === player &&
                it.resolveVehicleFlightStrategy() is FixedWingFlightStrategy
        }
    }

    private fun key(player: ServerPlayer, vehicle: VehicleEntity) = FixedWingPilotIntentKey(
        player.uuid, vehicle.id, vehicle.uuid, vehicle.level().dimension().location())

    @SubscribeEvent
    fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        sessions.remove(player.connection.connection)?.let(::finishReservation)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun mount(event: EntityMountEvent) {
        val player = event.entityMounting as? ServerPlayer ?: return
        sessions[player.connection.connection]?.let(::finishReservation)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun death(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        sessions[player.connection.connection]?.let(::finishReservation)
    }

    @SubscribeEvent
    fun leaveLevel(event: EntityLeaveLevelEvent) {
        if (event.level.isClientSide) return
        for (session in sessions.values) {
            val owner = session.reservedInput ?: continue
            if (owner.vehicle.get() === event.entity || owner.player.get() === event.entity) {
                val player = owner.player.get()
                // Tracking can end during a section move without removing the entity.
                if (event.entity.isRemoved || player == null ||
                    !reservationValid(player, session, System.nanoTime())) finishReservation(session)
            }
        }
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) { sessions.values.forEach(::finishReservation) }

    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) {
        sessions.values.forEach(::finishReservation)
        sessions.clear()
    }
}
