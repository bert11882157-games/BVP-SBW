package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.Consumer

/** Per-vehicle native owner for addon action sessions, authority, rollback, locks, and snapshots. */
class VehicleActionController(
    private val vehicle: VehicleEntity,
    private val snapshotPublisher: Consumer<String>,
) {
    private data class RequestKey(val operatorId: UUID, val actionId: ResourceLocation)
    private class InputState(var sequence: Int, var held: Boolean)

    private data class Session(
        val actionId: ResourceLocation,
        val operatorId: UUID,
        val action: VehicleAction,
        val context: VehicleActionContext,
        var inputHeld: Boolean = false,
    )

    private val sessions = LinkedHashMap<ResourceLocation, Session>()
    private var sessionSnapshot = emptyArray<Session>()
    private val inputStates = HashMap<RequestKey, InputState>()
    private val snapshots = LinkedHashMap<ResourceLocation, VehicleActionSnapshot>()
    private var snapshotSequence = 0
    private var clientPayload = ""
    private var lastPublishedPayload = ""

    /** Rejects cross-vehicle, stale, replayed, duplicate-edge, and cross-operator requests. */
    fun requestInput(
        player: Player,
        vehicleId: Int,
        actionId: ResourceLocation,
        requestSequence: Int,
        held: Boolean,
    ): Boolean {
        if (vehicle.level().isClientSide || vehicle.id != vehicleId || player.vehicle !== vehicle) return false
        if (vehicle.getSeatIndex(player) < 0 || !VehicleActionRegistry.isRegistered(actionId)) return false

        val requestKey = RequestKey(player.uuid, actionId)
        val inputState = inputStates[requestKey]
        if (inputState == null) {
            inputStates[requestKey] = InputState(requestSequence, held)
        } else {
            if (!isNewerSequence(requestSequence, inputState.sequence)) return false
            inputState.sequence = requestSequence
            if (inputState.held == held) return true
            inputState.held = held
        }

        var session = sessions[actionId]
        if (session != null && session.operatorId != player.uuid) return false
        if (!held && session == null) return true

        if (session == null) {
            val action = VehicleActionRegistry.create(vehicle, actionId) ?: return false
            val journal = VehicleActionTransactionJournal()
            session = Session(
                actionId,
                player.uuid,
                action,
                VehicleActionContext(vehicle, player.uuid, journal),
            )
            sessions[actionId] = session
            sessionsChanged()
        }

        session.inputHeld = held
        val update = safelyUpdate(session) { session.action.handleInput(session.context, held) }
        applyUpdate(
            session,
            update,
            if (update == VehicleActionUpdate.COMPLETE_ROLLBACK) {
                VehicleActionStopReason.INPUT_CANCELLED
            } else {
                VehicleActionStopReason.COMPLETED
            },
        )
        publishServerSnapshots()
        return true
    }

    fun tickServer() {
        if (vehicle.level().isClientSide) return

        for (session in currentSessions()) {
            if (sessions[session.actionId] !== session) continue
            val invalidReason = invalidReason(session)
            if (invalidReason != null) {
                stop(session, rollback = true, invalidReason)
                continue
            }

            val update = safelyUpdate(session) { session.action.tick(session.context) }
            applyUpdate(
                session,
                update,
                if (update == VehicleActionUpdate.COMPLETE_ROLLBACK) {
                    VehicleActionStopReason.ACTION_ROLLBACK
                } else {
                    VehicleActionStopReason.COMPLETED
                },
            )
        }

        enforceControlPolicy()
        pruneInputHistory()
        publishServerSnapshots()
    }

    fun controlPolicy(): VehicleActionControlPolicy {
        var combined: VehicleActionControlPolicy? = null
        for (session in currentSessions()) {
            if (sessions[session.actionId] !== session) continue
            val policy = try {
                session.action.controlPolicy(session.context)
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Vehicle action {} control policy failed on {}", session.actionId, vehicle, exception)
                VehicleActionControlPolicy.BLOCK_MOVEMENT_AND_FIRE
            }
            if (sessions[session.actionId] !== session) continue
            combined = combined?.combinedWith(policy) ?: policy
        }
        return combined ?: VehicleActionControlPolicy.ALLOW_ALL
    }

    fun allowsMovement(): Boolean = controlPolicy().allowsMovement

    fun allowsFire(): Boolean = controlPolicy().allowsFire

    fun enforceControlPolicy() {
        if (vehicle.level().isClientSide || allowsMovement()) return
        vehicle.forwardInputDown = false
        vehicle.backInputDown = false
        vehicle.leftInputDown = false
        vehicle.rightInputDown = false
        vehicle.targetSpeed = 0.0
        vehicle.power = 0F
        val motion = vehicle.deltaMovement
        vehicle.deltaMovement = Vec3(0.0, motion.y, 0.0)
    }

    fun snapshot(actionId: ResourceLocation): VehicleActionSnapshot? = snapshots[actionId]

    fun consumeClient(payload: String?) {
        if (!vehicle.level().isClientSide) return
        val nextPayload = payload.orEmpty()
        if (clientPayload == nextPayload) return
        clientPayload = nextPayload
        snapshots.clear()
        VehicleActionSnapshots.decode(nextPayload).forEach { snapshots[it.actionId] = it }
    }

    /** Persists only uncommitted deltas. Loading never resumes a stale operator session. */
    fun writeAdditionalSaveData(compound: CompoundTag) {
        if (vehicle.level().isClientSide) return
        val journals = CompoundTag()
        for (session in currentSessions()) {
            if (sessions[session.actionId] !== session) continue
            if (!session.context.journal.isEmpty()) {
                journals.put(session.actionId.toString(), session.context.journal.save())
            }
        }
        if (!journals.isEmpty) compound.put(ACTION_JOURNALS_TAG, journals)
    }

    /** Must run after native module state has loaded so module deltas can be subtracted exactly. */
    fun readAdditionalSaveData(compound: CompoundTag) {
        if (sessions.isNotEmpty()) {
            sessions.clear()
            sessionsChanged()
        }
        snapshots.clear()
        if (!vehicle.level().isClientSide && compound.contains(ACTION_JOURNALS_TAG)) {
            val journals = compound.getCompound(ACTION_JOURNALS_TAG)
            for (actionId in journals.allKeys) {
                VehicleActionTransactionJournal.load(journals.getCompound(actionId)).rollback(vehicle)
            }
        }
        publishServerSnapshots()
    }

    private fun invalidReason(session: Session): VehicleActionStopReason? {
        if (vehicle.isRemoved) return VehicleActionStopReason.VEHICLE_REMOVED
        if (vehicle.isWreck || vehicle.health <= 0F) return VehicleActionStopReason.VEHICLE_WRECKED
        if (session.context.operator() == null) return VehicleActionStopReason.OPERATOR_LEFT
        return null
    }

    private fun applyUpdate(
        session: Session,
        update: VehicleActionUpdate,
        stopReason: VehicleActionStopReason,
    ) {
        when (update) {
            VehicleActionUpdate.CONTINUE -> Unit
            VehicleActionUpdate.COMPLETE_COMMIT -> stop(session, rollback = false, stopReason)
            VehicleActionUpdate.COMPLETE_ROLLBACK -> stop(session, rollback = true, stopReason)
        }
    }

    private fun stop(session: Session, rollback: Boolean, reason: VehicleActionStopReason) {
        if (sessions[session.actionId] !== session) return
        if (rollback) session.context.journal.rollback(vehicle) else session.context.journal.commit()
        sessions.remove(session.actionId)
        sessionsChanged()
        snapshots.remove(session.actionId)
        try {
            session.action.onStopped(session.context, reason)
        } catch (exception: RuntimeException) {
            Mod.LOGGER.warn("Vehicle action {} stop callback failed on {}", session.actionId, vehicle, exception)
        }
    }

    private inline fun safelyUpdate(
        session: Session,
        update: () -> VehicleActionUpdate,
    ): VehicleActionUpdate = try {
        update()
    } catch (exception: RuntimeException) {
        Mod.LOGGER.warn("Vehicle action {} failed on {}", session.actionId, vehicle, exception)
        VehicleActionUpdate.COMPLETE_ROLLBACK
    }

    private fun publishServerSnapshots() {
        if (vehicle.level().isClientSide) return
        if (sessions.isEmpty() && snapshots.isEmpty() && lastPublishedPayload.isEmpty()) return
        snapshots.clear()
        for (session in currentSessions()) {
            if (sessions[session.actionId] !== session) continue
            val state = try {
                session.action.state(session.context)
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Vehicle action {} snapshot failed on {}", session.actionId, vehicle, exception)
                continue
            }
            if (sessions[session.actionId] !== session) continue
            snapshotSequence += 1
            snapshots[session.actionId] = VehicleActionSnapshot(
                session.actionId,
                snapshotSequence,
                vehicle.level().gameTime,
                session.operatorId,
                state.phaseId,
                state.phaseTicks.coerceAtLeast(0),
                state.targetId,
                session.inputHeld,
            )
        }
        val payload = VehicleActionSnapshots.encode(snapshots.values)
        lastPublishedPayload = payload
        snapshotPublisher.accept(payload)
    }

    private fun pruneInputHistory() {
        inputStates.keys.removeIf { key ->
            sessions[key.actionId]?.operatorId != key.operatorId &&
                    vehicle.passengers.none { it.uuid == key.operatorId }
        }
    }

    private fun isNewerSequence(candidate: Int, previous: Int): Boolean =
        candidate != previous && Integer.compareUnsigned(candidate, previous) > 0

    private fun sessionsChanged() {
        sessionSnapshot = sessions.values.toTypedArray()
    }

    private fun currentSessions(): Array<Session> = sessionSnapshot

    companion object {
        private const val ACTION_JOURNALS_TAG = "SBWVehicleActionJournals"
    }
}
