package com.atsuishio.superbwarfare.api.vehicle.flight

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Accepted world direction and ephemeral inputs; opposite switches mean manual neutral. */
data class FixedWingPilotIntent @JvmOverloads constructor(
    val directionX: Double,
    val directionY: Double,
    val directionZ: Double,
    val manualMask: Int = 0,
    val screenRollInput: Float? = null,
    val firstPerson: Boolean = false,
    val inversionRequested: Boolean = false,
) {
    init {
        val lengthSquared = directionX * directionX + directionY * directionY + directionZ * directionZ
        require(lengthSquared.isFinite() && kotlin.math.abs(lengthSquared - 1.0) <= 1.0e-9)
        require((manualMask and VALID_MASK) == manualMask)
        require(screenRollInput == null || (screenRollInput.isFinite() && screenRollInput in -1F..1F))
        require(!inversionRequested || screenRollInput != null)
    }

    companion object {
        const val PITCH_DOWN = 1
        const val PITCH_UP = 2
        const val ROLL_LEFT = 4
        const val ROLL_RIGHT = 8
        const val VALID_MASK = 15

        @JvmOverloads
        fun normalized(x: Double, y: Double, z: Double, mask: Int,
                       screenRollInput: Float? = null, firstPerson: Boolean = false,
                       inversionRequested: Boolean = false): FixedWingPilotIntent? {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || (mask and VALID_MASK) != mask) return null
            if (screenRollInput != null && (!screenRollInput.isFinite() || screenRollInput !in -1F..1F)) return null
            if (inversionRequested && screenRollInput == null) return null
            val squared = x * x + y * y + z * z
            if (!squared.isFinite() || squared !in 0.998001..1.002001) return null
            val inverse = 1.0 / sqrt(squared)
            return FixedWingPilotIntent(x * inverse, y * inverse, z * inverse, mask,
                screenRollInput, firstPerson, inversionRequested)
        }
    }
}

/** Operator-only observation. It does not contain attitude, velocity, or accepted surface authority. */
data class FixedWingPilotIntentSnapshot(
    val controlEpoch: Long,
    val acceptedSequence: Long,
    val serverTick: Long,
    val intent: FixedWingPilotIntent,
)

/** Server-thread lease. Aim persists; missing refresh releases held controls, never replays deltas. */
class FixedWingPilotIntentState {
    private var owner: UUID? = null
    private var value: FixedWingPilotIntent? = null
    private var receivedTick = Long.MIN_VALUE
    private var sequence = -1L
    var controlEpoch: Long = newEpoch()
        private set

    fun bind(controller: UUID?, x: Double, y: Double, z: Double) {
        if (owner == controller && (controller == null || value != null)) return
        clear()
        if (controller == null) return
        val initial = FixedWingPilotIntent.normalized(x, y, z, 0) ?: return
        owner = controller
        value = initial
    }

    fun clear() {
        owner = null
        value = null
        receivedTick = Long.MIN_VALUE
        sequence = -1L
        controlEpoch = newEpoch()
    }

    @JvmOverloads
    fun offer(
        controller: UUID, epoch: Long, nextSequence: Long, serverTick: Long,
        x: Double, y: Double, z: Double, manualMask: Int,
        screenRollInput: Float? = null, firstPerson: Boolean = false,
        inversionRequested: Boolean = false,
    ): Boolean {
        if (owner != controller || epoch != controlEpoch || nextSequence < 0L ||
            nextSequence <= sequence || serverTick < 0L ||
            (receivedTick != Long.MIN_VALUE && serverTick < receivedTick)
        ) return false
        val next = FixedWingPilotIntent.normalized(x, y, z, manualMask,
            screenRollInput, firstPerson, inversionRequested) ?: return false
        value = next
        sequence = nextSequence
        receivedTick = serverTick
        return true
    }

    fun sample(serverTick: Long): FixedWingPilotIntent? {
        val current = value ?: return null
        if ((current.manualMask != 0 || current.firstPerson) && (serverTick < receivedTick ||
                receivedTick == Long.MIN_VALUE || serverTick - receivedTick > MANUAL_TIMEOUT_TICKS)) {
            // Held keys expire for safety. The target and its screen-side guidance remain one
            // coherent request; a delayed client must not switch automatic turn-plane policy.
            return current.copy(manualMask = 0, firstPerson = false).also { value = it }
        }
        return current
    }

    fun snapshot(serverTick: Long): FixedWingPilotIntentSnapshot? =
        sample(serverTick)?.let { FixedWingPilotIntentSnapshot(controlEpoch, sequence, serverTick, it) }

    companion object {
        const val MANUAL_TIMEOUT_TICKS = 10L
        private val epochs = AtomicLong()
        private fun newEpoch(): Long = epochs.incrementAndGet().also {
            check(it > 0L) { "Fixed-wing control epoch exhausted" }
        }
    }
}
