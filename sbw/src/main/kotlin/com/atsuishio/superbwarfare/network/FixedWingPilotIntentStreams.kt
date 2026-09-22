package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntentSnapshot
import com.atsuishio.superbwarfare.network.message.receive.FixedWingPilotIntentStateMessage
import com.atsuishio.superbwarfare.network.message.send.FixedWingPilotIntentMessage
import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Weapon selection is deliberately absent from the pilot lease. */
data class FixedWingPilotIntentKey(
    val operatorUuid: UUID,
    val vehicleId: Int,
    val vehicleUuid: UUID,
    val dimension: ResourceLocation,
)

@Serializable
data class FixedWingPilotIntentWireValue @JvmOverloads constructor(
    val acceptedSequence: Long,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val manualMask: Byte,
    val screenRollInput: Float? = null,
    val firstPerson: Boolean = false,
    val inversionRequested: Boolean = false,
) {
    fun intent(): FixedWingPilotIntent? =
        FixedWingPilotIntent.normalized(worldX, worldY, worldZ, manualMask.toInt(),
            screenRollInput, firstPerson, inversionRequested)
}

object FixedWingPilotIntentPublication {
    fun shouldPublish(previous: FixedWingPilotIntentSnapshot?, current: FixedWingPilotIntentSnapshot,
                      lastPublishTick: Long, centered: Boolean): Boolean {
        val changed = previous == null || previous.controlEpoch != current.controlEpoch ||
            previous.intent != current.intent
        // A neutral first input claims the lease without changing its direction or switches.
        val firstAcceptance = previous?.acceptedSequence == -1L && current.acceptedSequence >= 0L
        val bootstrapDue = current.acceptedSequence == -1L &&
            (lastPublishTick == Long.MIN_VALUE ||
                current.serverTick - lastPublishTick >= FixedWingPilotIntentMailbox.HEARTBEAT_TICKS)
        return changed || firstAcceptance || centered || bootstrapDue
    }
}

/** One replaceable input and a replay/rate watermark, retained for the connection lifetime. */
class FixedWingPilotIntentMailbox {
    private var sequence = -1L
    private var tokens = BURST.toDouble()
    private var updatedNanos = Long.MIN_VALUE
    private var pending: FixedWingPilotIntentMessage? = null
    var reservation: FixedWingPilotInputReservation? = null
        private set

    fun reserve(
        key: FixedWingPilotIntentKey, runToken: UUID, epoch: Long,
        serverTick: Long, nowNanos: Long, durationTicks: Int,
    ): FixedWingPilotInputReservation? {
        if (reservation != null ||
            !FixedWingPilotInputReservation.validRequest(runToken, epoch, serverTick, durationTicks)
        ) return null
        discardPending()
        return FixedWingPilotInputReservation(key, runToken, epoch, serverTick, durationTicks,
            nowNanos).also { reservation = it }
    }

    fun release(reservation: FixedWingPilotInputReservation): Boolean {
        if (this.reservation !== reservation) return false
        discardPending()
        this.reservation = null
        return true
    }

    fun offer(
        message: FixedWingPilotIntentMessage,
        key: FixedWingPilotIntentKey,
        issuedEpoch: Long,
        nowNanos: Long,
    ): Boolean {
        if (reservation != null) return false
        if (message.vehicleId < 0 || message.vehicleId != key.vehicleId ||
            message.vehicleUuid != key.vehicleUuid || message.dimension != key.dimension ||
            issuedEpoch <= 0L || message.controlEpoch != issuedEpoch ||
            message.sequence < 0L || message.sequence <= sequence ||
            FixedWingPilotIntent.normalized(message.worldX, message.worldY, message.worldZ,
                message.manualMask.toInt(), message.screenRollInput, message.firstPerson,
                message.inversionRequested) == null
        ) return false
        if (updatedNanos != Long.MIN_VALUE) {
            if (nowNanos < updatedNanos) return false
            tokens = minOf(BURST.toDouble(), tokens +
                (nowNanos - updatedNanos).toDouble() / 1_000_000_000.0 * RATE)
        }
        updatedNanos = nowNanos
        if (tokens < 1.0) return false
        tokens -= 1.0
        sequence = message.sequence
        // A later held snapshot cannot erase an already admitted Home edge in the same lease.
        val center = message.centerAim || pending?.let {
            it.controlEpoch == message.controlEpoch && it.centerAim
        } == true
        pending = if (center) message.copy(centerAim = true, screenRollInput = null,
            inversionRequested = false) else message
        return true
    }

    fun take(): FixedWingPilotIntentMessage? =
        if (reservation != null) null else pending.also { pending = null }
    fun discardPending() { pending = null }

    companion object {
        const val RATE = 20
        const val BURST = 40
        const val HEARTBEAT_TICKS = 5L
    }
}

/** Pure client-side lease/queue state. Resolution never sends a packet or changes the target. */
class FixedWingPilotIntentClientStream {
    data class View @JvmOverloads constructor(
        val controlEpoch: Long,
        val acceptedSequence: Long,
        val serverTick: Long,
        val directionX: Double,
        val directionY: Double,
        val directionZ: Double,
        val manualMask: Int,
        val centeringPending: Boolean,
        val screenRollInput: Float? = null,
        val firstPerson: Boolean = false,
        val inversionRequested: Boolean = false,
    )

    private var key: FixedWingPilotIntentKey? = null
    private var retiredEpoch = 0L
    private var epoch = 0L
    private var acceptedSequence = -1L
    private var serverTick = -1L
    private var nextSequence = 0L
    private var lastSentSequence = -1L
    private var lastSentTick = Long.MIN_VALUE
    private var intent: FixedWingPilotIntent? = null
    private var dirty = false
    private var centerQueued = false
    private var centerSequence = -1L
    private var desiredManualMask = 0
    private var desiredFirstPerson = false

    fun bind(next: FixedWingPilotIntentKey?) {
        if (key == next) return
        retire()
        key = next
    }

    /** Called for a real local mount edge even when a same-seat ABA leaves equal identity fields. */
    fun retire() {
        retiredEpoch = maxOf(retiredEpoch, epoch)
        epoch = 0L
        acceptedSequence = -1L
        serverTick = -1L
        intent = null
        dirty = false
        centerQueued = false
        centerSequence = -1L
        desiredManualMask = 0
        desiredFirstPerson = false
        lastSentTick = Long.MIN_VALUE
    }

    fun resetConnection() {
        key = null
        retire()
        retiredEpoch = 0L
        nextSequence = 0L
        lastSentSequence = -1L
    }

    fun view(): View? = intent?.let {
        View(epoch, acceptedSequence, serverTick, it.directionX, it.directionY, it.directionZ,
            it.manualMask, centerQueued || centerSequence >= 0L, it.screenRollInput, it.firstPerson,
            it.inversionRequested)
    }

    @JvmOverloads
    fun offer(x: Double, y: Double, z: Double, manualMask: Int, centerAim: Boolean,
              screenRollInput: Float? = null, firstPerson: Boolean = false,
              inversionRequested: Boolean = false): Boolean {
        val current = intent ?: return false
        val next = FixedWingPilotIntent.normalized(x, y, z, manualMask,
            screenRollInput, firstPerson, inversionRequested) ?: return false
        desiredManualMask = manualMask
        desiredFirstPerson = firstPerson
        // Do not send the old target back over the server's not-yet-acknowledged Home result.
        if (centerSequence >= 0L) return true
        val value = when {
            centerQueued -> current.copy(manualMask = manualMask, screenRollInput = null,
                firstPerson = firstPerson, inversionRequested = false)
            centerAim -> next.copy(screenRollInput = null, inversionRequested = false)
            else -> next
        }
        if (current != value) { intent = value; dirty = true }
        if (centerAim) { centerQueued = true; dirty = true }
        return true
    }

    fun releaseManual() {
        desiredManualMask = 0
        desiredFirstPerson = false
        centerQueued = false
        val current = intent ?: return
        if (current.manualMask != 0 || current.screenRollInput != null || current.firstPerson ||
            current.inversionRequested) {
            intent = current.copy(manualMask = 0, screenRollInput = null, firstPerson = false,
                inversionRequested = false)
            dirty = true
        }
    }

    /** Missing projection clears acquisition metadata without releasing manual controls. */
    fun clearScreenGuidance() {
        val current = intent ?: return
        if (current.screenRollInput == null && !current.inversionRequested) return
        intent = current.copy(screenRollInput = null, inversionRequested = false)
        dirty = true
    }

    /** Perspective-only refresh must not overwrite keys queued while Home awaits its ACK. */
    fun setFirstPerson(firstPerson: Boolean) {
        desiredFirstPerson = firstPerson
        val current = intent ?: return
        if (centerSequence >= 0L || current.firstPerson == firstPerson) return
        intent = current.copy(firstPerson = firstPerson)
        dirty = true
    }

    fun nextMessage(clientTick: Long): FixedWingPilotIntentMessage? {
        val context = key ?: return null
        val current = intent ?: return null
        if (clientTick < 0L || clientTick == lastSentTick || centerSequence >= 0L ||
            nextSequence == Long.MAX_VALUE
        ) return null
        if (lastSentTick != Long.MIN_VALUE && clientTick < lastSentTick) { retire(); return null }
        if (!dirty && lastSentTick != Long.MIN_VALUE &&
            clientTick - lastSentTick < FixedWingPilotIntentMailbox.HEARTBEAT_TICKS
        ) return null
        val sequence = nextSequence++
        val message = FixedWingPilotIntentMessage(context.vehicleId, context.vehicleUuid,
            context.dimension, epoch, sequence, current.directionX, current.directionY,
            current.directionZ, current.manualMask.toByte(), centerQueued, current.screenRollInput,
            current.firstPerson, current.inversionRequested)
        lastSentTick = clientTick
        lastSentSequence = sequence
        if (centerQueued) centerSequence = sequence
        centerQueued = false
        dirty = false
        return message
    }

    fun accept(message: FixedWingPilotIntentStateMessage): Boolean {
        val context = key ?: return false
        if (!message.valid() || message.operatorUuid != context.operatorUuid ||
            message.vehicleId != context.vehicleId || message.vehicleUuid != context.vehicleUuid ||
            message.dimension != context.dimension || message.controlEpoch <= retiredEpoch ||
            message.controlEpoch < epoch
        ) return false
        if (!message.active) {
            retiredEpoch = maxOf(retiredEpoch, message.controlEpoch)
            retire()
            return true
        }
        val value = message.value ?: return false
        val observed = value.intent() ?: return false
        if (message.controlEpoch > epoch) {
            // A newly issued lease has no accepted client sample. Never adopt an unexplained ACK.
            if (value.acceptedSequence != -1L) return false
            retire()
            epoch = message.controlEpoch
            intent = observed
            desiredManualMask = observed.manualMask
            desiredFirstPerson = observed.firstPerson
            acceptedSequence = -1L
            serverTick = message.serverTick
            dirty = true
            return true
        }
        if (value.acceptedSequence < acceptedSequence || value.acceptedSequence > lastSentSequence ||
            message.serverTick < serverTick ||
            (value.acceptedSequence == acceptedSequence && message.serverTick == serverTick)
        ) return false
        acceptedSequence = value.acceptedSequence
        serverTick = message.serverTick
        if (centerSequence >= 0L && value.acceptedSequence >= centerSequence) {
            intent = observed.copy(manualMask = desiredManualMask, screenRollInput = null,
                firstPerson = desiredFirstPerson, inversionRequested = false)
            centerSequence = -1L
            dirty = true
        }
        return true
    }
}
