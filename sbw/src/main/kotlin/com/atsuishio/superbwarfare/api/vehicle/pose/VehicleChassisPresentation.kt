package com.atsuishio.superbwarfare.api.vehicle.pose

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/** Immutable result consumed by every client presentation transform for one render sample. */
data class VehicleChassisPresentation(
    val pose: VehiclePoseSnapshot,
    val anchor: Vec3,
    val chassisYawDegrees: Float,
    val presentationServerTick: Double,
    val alpha: Float,
    val lowerSequence: Int,
    val upperSequence: Int,
    val bufferDepth: Int,
    val mode: Mode,
    val localHorizontalCorrection: Double = 0.0,
    val localComposedYCorrection: Double = 0.0,
    val localYawCorrection: Float = 0F,
) {
    enum class Mode {
        AUTHORITATIVE,
        LEGACY,
        REMOTE_BASELINE,
        REMOTE_INTERPOLATED,
        REMOTE_HELD,
        LOCAL_PREDICTED,
        LOCAL_RECONCILED,
        DISCONTINUITY,
    }

    val sequence: Int get() = pose.sequence
    val serverTick: Long get() = pose.serverTick
    val resolvedWorldY: Double get() = anchor.y + pose.verticalOffset
}

/**
 * Client-only bounded schema-2 presentation state. Packet receipt only offers immutable samples;
 * client ticks advance the remote cursor or local correction. No method mutates an entity.
 */
class VehicleChassisPresentationTimeline {
    private data class Prediction(val serverTick: Long, val anchor: Vec3, val yaw: Float)

    private enum class ClientOwner {
        NONE,
        LOCAL,
        REMOTE,
    }

    private val samples = ArrayList<VehiclePoseSnapshot>(MAX_SAMPLES)
    private val predictions = ArrayList<Prediction>(MAX_PREDICTIONS)
    private var remoteCursor = Double.NaN
    private var remoteLastClientTick = Int.MIN_VALUE
    private var remoteRecoveryPending = false
    private var remoteBaselinePending = false
    private var remoteWarming = false
    private var remoteSoftRebasePending = false
    private var remoteRebuffering = false
    private var remoteRebufferWaitTicks = 0
    private var remoteLastNewestServerTick = Long.MIN_VALUE
    private var localProcessedSequence = 0
    private var localHasCorrespondence = false
    private var localPosePrevious: VehiclePoseSnapshot? = null
    private var localPoseCurrent: VehiclePoseSnapshot? = null
    private var correctionPrevious = Vec3.ZERO
    private var correctionCurrent = Vec3.ZERO
    private var correctionTarget = Vec3.ZERO
    private var yawCorrectionPrevious = 0F
    private var yawCorrectionCurrent = 0F
    private var yawCorrectionTarget = 0F
    private var hardResetThisTick = false
    private var clientOwner = ClientOwner.NONE

    val depth: Int get() = samples.size

    fun clear() {
        samples.clear()
        resetRemoteClock()
        resetLocalState()
        clientOwner = ClientOwner.NONE
        remoteRecoveryPending = false
        remoteBaselinePending = false
        remoteSoftRebasePending = false
        remoteRebuffering = false
        remoteRebufferWaitTicks = 0
        remoteLastNewestServerTick = Long.MIN_VALUE
    }

    private fun resetRemoteClock() {
        remoteCursor = Double.NaN
        remoteLastClientTick = Int.MIN_VALUE
        remoteWarming = false
        remoteSoftRebasePending = false
        remoteRebuffering = false
        remoteRebufferWaitTicks = 0
        remoteLastNewestServerTick = Long.MIN_VALUE
    }

    private fun resetLocalState(processedSequence: Int = 0) {
        predictions.clear()
        localProcessedSequence = processedSequence
        localHasCorrespondence = false
        localPosePrevious = null
        localPoseCurrent = null
        correctionPrevious = Vec3.ZERO
        correctionCurrent = Vec3.ZERO
        correctionTarget = Vec3.ZERO
        yawCorrectionPrevious = 0F
        yawCorrectionCurrent = 0F
        yawCorrectionTarget = 0F
        hardResetThisTick = false
    }

    /** Local prediction becomes active without replaying state from an earlier control session. */
    fun beginLocal(predictedAnchor: Vec3, predictedYaw: Float): Boolean {
        if (clientOwner == ClientOwner.LOCAL) return false
        val continuitySample = if (clientOwner == ClientOwner.REMOTE) resolveRemote(0F) else null
        clientOwner = ClientOwner.LOCAL
        resetRemoteClock()
        // The retained newest sample predates this ownership epoch. Wait for the next sample with
        // a matching prediction instead of reconciling old authority against the current entity.
        resetLocalState(samples.lastOrNull()?.sequence ?: 0)
        if (continuitySample != null && finite(predictedAnchor) && predictedYaw.isFinite()) {
            localPosePrevious = continuitySample.pose
            localPoseCurrent = continuitySample.pose
            val horizontal = Vec3(
                continuitySample.anchor.x - predictedAnchor.x,
                0.0,
                continuitySample.anchor.z - predictedAnchor.z,
            )
            val composedY = continuitySample.resolvedWorldY - predictedAnchor.y
            val yaw = Mth.wrapDegrees(continuitySample.chassisYawDegrees - predictedYaw)
            if (horizontal.horizontalDistance() <= HARD_POSITION_RESET &&
                abs(composedY) <= HARD_POSITION_RESET && abs(yaw) <= HARD_YAW_RESET
            ) {
                val correction = Vec3(horizontal.x, composedY, horizontal.z)
                correctionPrevious = correction
                correctionCurrent = correction
                correctionTarget = correction
                yawCorrectionPrevious = yaw
                yawCorrectionCurrent = yaw
                yawCorrectionTarget = yaw
            }
        }
        return true
    }

    /** Remote presentation always re-enters at the recent fixed-delay target, never a frozen cursor. */
    fun beginRemote(clientTick: Int): Boolean {
        if (clientOwner == ClientOwner.REMOTE) return false
        clientOwner = ClientOwner.REMOTE
        resetLocalState(samples.lastOrNull()?.sequence ?: 0)
        resetRemoteClock()
        remoteLastClientTick = clientTick
        val newest = samples.lastOrNull()
        val retainedTailNeedsRecovery = !remoteBaselinePending && retainedTailRequiresRecovery()
        if (newest != null && !retainedTailNeedsRecovery) {
            remoteRecoveryPending = false
            remoteCursor = newest.serverTick.toDouble() - OBSERVER_DELAY_TICKS
            remoteBaselinePending = false
            remoteLastNewestServerTick = newest.serverTick
        } else if (retainedTailNeedsRecovery) {
            remoteRecoveryPending = true
        }
        return true
    }

    /** Provider/flight applicability exits invalidate both ownership epochs but retain bounded samples. */
    fun suspendClientOwnership(): Boolean {
        if (clientOwner == ClientOwner.NONE) return false
        clientOwner = ClientOwner.NONE
        resetRemoteClock()
        resetLocalState(samples.lastOrNull()?.sequence ?: 0)
        return true
    }

    /** Accepted, finite, monotonically newer samples enter here; render phase is never changed. */
    fun offer(snapshot: VehiclePoseSnapshot, discontinuity: Boolean = false) {
        val anchor = snapshot.anchor ?: return
        if (!finite(anchor) || !snapshot.chassisYawDegrees.isFinite()) return
        val previous = samples.lastOrNull()
        if (previous != null && !snapshot.isNewerThan(previous)) return

        val tickGap = previous?.let { snapshot.serverTick - it.serverTick } ?: 0L
        val sequenceStep = previous?.let { snapshot.sequence - it.sequence } ?: 0
        val spatialReset = previous != null && isSpatialDiscontinuity(previous, snapshot)
        val safeChangedOnlyIdleGap = previous != null && tickGap > 1L &&
            sequenceStep == 1 && !spatialReset && !discontinuity
        if (safeChangedOnlyIdleGap) {
            // Sequence +1 proves no changed sample was lost. The preceding state was held until
            // the tick before this change, so materialize that known bracket without a wire write.
            val heldTick = snapshot.serverTick - 1L
            if (heldTick > previous.serverTick) {
                samples.add(previous.copy(serverTick = heldTick))
                remoteSoftRebasePending = true
            }
        }
        samples.add(snapshot)
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)

        val unknownGap = previous != null && (tickGap <= 0L || sequenceStep != 1)
        if (previous == null) remoteBaselinePending = true
        if (discontinuity || unknownGap || spatialReset) remoteRecoveryPending = true
        if (discontinuity) remoteBaselinePending = false
    }

    fun advanceRemote(clientTick: Int) {
        val newest = samples.lastOrNull() ?: return
        if (remoteRecoveryPending || remoteCursor.isNaN()) {
            val immediateBaseline = remoteRecoveryPending && !remoteBaselinePending
            remoteCursor = if (immediateBaseline) {
                newest.serverTick.toDouble()
            } else {
                newest.serverTick.toDouble() - OBSERVER_DELAY_TICKS
            }
            remoteWarming = immediateBaseline
            remoteRecoveryPending = false
            remoteBaselinePending = false
            remoteLastClientTick = clientTick
            remoteLastNewestServerTick = newest.serverTick
            return
        }
        if (remoteWarming) {
            remoteLastClientTick = clientTick
            remoteLastNewestServerTick = newest.serverTick
            if (newest.serverTick - remoteCursor >= OBSERVER_DELAY_TICKS) remoteWarming = false
            return
        }
        val delayedCursor = newest.serverTick.toDouble() - OBSERVER_DELAY_TICKS
        if (remoteSoftRebasePending) {
            // Advancing across the synthesized held interval changes time but not presentation.
            remoteCursor = maxOf(remoteCursor, delayedCursor)
            remoteSoftRebasePending = false
            remoteRebuffering = false
            remoteRebufferWaitTicks = 0
            remoteLastClientTick = clientTick
            remoteLastNewestServerTick = newest.serverTick
            return
        }
        val newestAdvanced = newest.serverTick > remoteLastNewestServerTick
        remoteLastNewestServerTick = maxOf(remoteLastNewestServerTick, newest.serverTick)
        if (newestAdvanced && remoteCursor >= delayedCursor && !remoteRebuffering) {
            remoteRebuffering = true
            remoteRebufferWaitTicks = 0
            if (remoteCursor == delayedCursor) {
                // The prior no-authority frame already rendered through cursor + 1. Preserve that
                // displayed boundary instead of replaying the same bracket from cursor at partial 0.
                remoteCursor = (remoteCursor + 1.0).coerceAtMost(newest.serverTick.toDouble())
                remoteLastClientTick = clientTick
                return
            }
        }
        if (remoteRebuffering) {
            remoteLastClientTick = clientTick
            if (delayedCursor < remoteCursor) {
                remoteRebufferWaitTicks += 1
                if (remoteRebufferWaitTicks <= OBSERVER_DELAY_TICKS.toInt()) return
                // Changed-only transport may make N+1 the final stop sample. After the bounded
                // wait, drain forward to it rather than waiting forever for an N+2 that may not exist.
                remoteRebuffering = false
                remoteRebufferWaitTicks = 0
            } else {
                remoteRebuffering = false
                remoteRebufferWaitTicks = 0
                return
            }
        }
        val elapsed = (clientTick - remoteLastClientTick).coerceIn(0, MAX_CLIENT_ADVANCE)
        remoteLastClientTick = clientTick
        remoteCursor = (remoteCursor + elapsed).coerceAtMost(newest.serverTick.toDouble())
    }

    fun resolveRemote(partialTicks: Float): VehicleChassisPresentation? {
        if (samples.isEmpty()) return null
        val newestBoundary = samples.last().serverTick.toDouble() + MAX_EXTRAPOLATION_TICKS
        val target = if (remoteCursor.isNaN()) {
            samples.last().serverTick.toDouble() - OBSERVER_DELAY_TICKS
        } else if (remoteWarming || remoteRebuffering) {
            remoteCursor
        } else {
            (remoteCursor + partialTicks.coerceIn(0F, 1F)).coerceAtMost(newestBoundary)
        }
        val first = samples.first()
        if (target <= first.serverTick) return complete(first, target, 0F, first, first,
            VehicleChassisPresentation.Mode.REMOTE_BASELINE)

        for (index in 1 until samples.size) {
            val lower = samples[index - 1]
            val upper = samples[index]
            if (target > upper.serverTick) continue
            val gap = upper.serverTick - lower.serverTick
            if (gap <= 0 || gap > MAX_INTERPOLATED_GAP) {
                return complete(upper, target, 1F, upper, upper,
                    VehicleChassisPresentation.Mode.REMOTE_BASELINE)
            }
            val alpha = ((target - lower.serverTick) / gap.toDouble()).toFloat().coerceIn(0F, 1F)
            val pose = VehiclePoseSnapshot.interpolate(lower, upper, alpha)
            return complete(pose, target, alpha, lower, upper,
                VehicleChassisPresentation.Mode.REMOTE_INTERPOLATED)
        }

        val newest = samples.last()
        return complete(newest, target, 1F, newest, newest,
            VehicleChassisPresentation.Mode.REMOTE_HELD)
    }

    /**
     * Read-only authoritative history lookup for other presentation streams that carry the same
     * server tick. This never advances either ownership clock and never extrapolates motion.
     * Changed-only state may be held after the newest accepted sample; an unprovable interior gap
     * fails closed so a consumer can retain its last coherent immutable tuple.
     */
    fun resolveAuthoritativeAt(serverTick: Double): VehicleChassisPresentation? {
        if (!serverTick.isFinite() || samples.isEmpty()) return null
        val first = samples.first()
        if (serverTick < first.serverTick.toDouble()) return null
        for (index in 1 until samples.size) {
            val lower = samples[index - 1]
            val upper = samples[index]
            if (serverTick > upper.serverTick.toDouble()) continue
            val gap = upper.serverTick - lower.serverTick
            if (gap <= 0L) return null
            val syntheticHeldBridge = lower.sequence == upper.sequence &&
                lower.hasSameChassisSample(upper)
            val ordinarySequenceStep = upper.sequence - lower.sequence == 1
            if (!syntheticHeldBridge && !ordinarySequenceStep) {
                return if (serverTick == upper.serverTick.toDouble()) complete(
                    upper,
                    serverTick,
                    1F,
                    upper,
                    upper,
                    VehicleChassisPresentation.Mode.REMOTE_BASELINE,
                ) else null
            }
            if (gap > MAX_INTERPOLATED_GAP) {
                return if (syntheticHeldBridge) complete(
                    lower,
                    serverTick,
                    0F,
                    lower,
                    upper,
                    VehicleChassisPresentation.Mode.REMOTE_HELD,
                ) else null
            }
            val alpha = ((serverTick - lower.serverTick.toDouble()) / gap.toDouble())
                .toFloat().coerceIn(0F, 1F)
            val pose = VehiclePoseSnapshot.interpolate(lower, upper, alpha)
            return complete(
                pose,
                serverTick,
                alpha,
                lower,
                upper,
                VehicleChassisPresentation.Mode.REMOTE_INTERPOLATED,
            )
        }
        val newest = samples.last()
        return complete(
            newest,
            serverTick,
            1F,
            newest,
            newest,
            VehicleChassisPresentation.Mode.REMOTE_HELD,
        )
    }

    /** Rendering follows the owner latched at the vehicle tick boundary, not live passenger state. */
    fun resolveOwned(
        partialTicks: Float,
        predictedAnchor: Vec3,
        predictedYaw: Float,
    ): VehicleChassisPresentation? = when (clientOwner) {
        ClientOwner.LOCAL -> resolveLocal(partialTicks, predictedAnchor, predictedYaw)
        ClientOwner.REMOTE -> resolveRemote(partialTicks)
        ClientOwner.NONE -> null
    }

    fun recordLocalPrediction(serverTick: Long, anchor: Vec3, yaw: Float) {
        if (!finite(anchor) || !yaw.isFinite()) return
        predictions.removeAll { it.serverTick == serverTick }
        predictions.add(Prediction(serverTick, anchor, yaw))
        while (predictions.size > MAX_PREDICTIONS) predictions.removeAt(0)
    }

    fun advanceLocal() {
        hardResetThisTick = false
        val incoming = samples.lastOrNull()
        if (incoming == null || incoming.sequence == localProcessedSequence) {
            // The A -> B pose window is rendered exactly once. On unchanged ticks collapse it to
            // B while the independently rate-limited correction continues to advance.
            localPosePrevious = localPoseCurrent
            advanceCorrection()
            return
        }
        localProcessedSequence = incoming.sequence
        val predicted = predictions.firstOrNull { it.serverTick == incoming.serverTick }
        if (predicted == null) {
            localHasCorrespondence = false
            // Keep the authoritative pose current without inventing a position correspondence.
            // Any prior correction target is no longer provable and decays through the same caps.
            localPosePrevious = localPoseCurrent ?: incoming
            localPoseCurrent = incoming
            correctionTarget = Vec3.ZERO
            yawCorrectionTarget = 0F
            advanceCorrection()
            return
        }
        if (!localHasCorrespondence) {
            localHasCorrespondence = true
            localPosePrevious = incoming
            localPoseCurrent = incoming
            correctionPrevious = correctionCurrent
            yawCorrectionPrevious = yawCorrectionCurrent
            return
        }

        val anchor = incoming.anchor ?: return
        val horizontalError = Vec3(anchor.x - predicted.anchor.x, 0.0, anchor.z - predicted.anchor.z)
        val composedYError = anchor.y + incoming.verticalOffset - predicted.anchor.y
        val yawError = Mth.wrapDegrees(incoming.chassisYawDegrees - predicted.yaw)
        correctionTarget = Vec3(
            if (horizontalError.horizontalDistance() <= POSITION_DEADBAND) 0.0 else horizontalError.x,
            if (abs(composedYError) <= POSITION_DEADBAND) 0.0 else composedYError,
            if (horizontalError.horizontalDistance() <= POSITION_DEADBAND) 0.0 else horizontalError.z,
        )
        yawCorrectionTarget = if (abs(yawError) <= YAW_DEADBAND) 0F else yawError
        localPosePrevious = localPoseCurrent ?: incoming
        localPoseCurrent = incoming

        if (horizontalError.horizontalDistance() > HARD_POSITION_RESET ||
            abs(composedYError) > HARD_POSITION_RESET || abs(yawError) > HARD_YAW_RESET
        ) {
            correctionPrevious = correctionTarget
            correctionCurrent = correctionTarget
            yawCorrectionPrevious = yawCorrectionTarget
            yawCorrectionCurrent = yawCorrectionTarget
            hardResetThisTick = true
        } else {
            advanceCorrection()
        }
    }

    fun resolveLocal(
        partialTicks: Float,
        predictedAnchor: Vec3,
        predictedYaw: Float,
    ): VehicleChassisPresentation? {
        val current = localPoseCurrent ?: samples.lastOrNull() ?: return null
        val previous = localPosePrevious ?: current
        val alpha = partialTicks.coerceIn(0F, 1F)
        val pose = if (previous === current) current else VehiclePoseSnapshot.interpolate(previous, current, alpha)
        val correction = Vec3(
            Mth.lerp(alpha.toDouble(), correctionPrevious.x, correctionCurrent.x),
            Mth.lerp(alpha.toDouble(), correctionPrevious.y, correctionCurrent.y),
            Mth.lerp(alpha.toDouble(), correctionPrevious.z, correctionCurrent.z),
        )
        val yawCorrection = Mth.lerp(alpha, yawCorrectionPrevious, yawCorrectionCurrent)
        val composedWorldY = predictedAnchor.y + correction.y
        val resolvedAnchor = Vec3(
            predictedAnchor.x + correction.x,
            composedWorldY - pose.verticalOffset,
            predictedAnchor.z + correction.z,
        )
        val resolvedYaw = predictedYaw + yawCorrection
        val resolvedPose = pose.copy(anchor = resolvedAnchor, chassisYawDegrees = resolvedYaw)
        return VehicleChassisPresentation(
            resolvedPose,
            resolvedAnchor,
            resolvedYaw,
            pose.serverTick + alpha.toDouble(),
            alpha,
            previous.sequence,
            current.sequence,
            samples.size,
            if (hardResetThisTick) VehicleChassisPresentation.Mode.DISCONTINUITY
            else if (correction.lengthSqr() > 1.0E-12 || abs(yawCorrection) > 1.0E-5F)
                VehicleChassisPresentation.Mode.LOCAL_RECONCILED
            else VehicleChassisPresentation.Mode.LOCAL_PREDICTED,
            correction.horizontalDistance(),
            correction.y,
            yawCorrection,
        )
    }

    private fun advanceCorrection() {
        correctionPrevious = correctionCurrent
        yawCorrectionPrevious = yawCorrectionCurrent
        val remaining = correctionTarget.subtract(correctionCurrent)
        var horizontal = Vec3(remaining.x * SOFT_FACTOR, 0.0, remaining.z * SOFT_FACTOR)
        if (horizontal.horizontalDistance() > MAX_HORIZONTAL_CORRECTION) {
            horizontal = horizontal.normalize().scale(MAX_HORIZONTAL_CORRECTION)
        }
        val vertical = (remaining.y * SOFT_FACTOR)
            .coerceIn(-MAX_COMPOSED_Y_CORRECTION, MAX_COMPOSED_Y_CORRECTION)
        correctionCurrent = correctionCurrent.add(horizontal.x, vertical, horizontal.z)
        val yawStep = (Mth.wrapDegrees(yawCorrectionTarget - yawCorrectionCurrent) * SOFT_FACTOR.toFloat())
            .coerceIn(-MAX_YAW_CORRECTION, MAX_YAW_CORRECTION)
        yawCorrectionCurrent += yawStep
    }

    private fun retainedTailRequiresRecovery(): Boolean {
        val newest = samples.lastOrNull() ?: return true
        val target = newest.serverTick.toDouble() - OBSERVER_DELAY_TICKS
        val lowerIndex = samples.indexOfLast { it.serverTick.toDouble() <= target }
        if (lowerIndex < 0) return true
        for (index in (lowerIndex + 1)..samples.lastIndex) {
            val lower = samples[index - 1]
            val upper = samples[index]
            val gap = upper.serverTick - lower.serverTick
            val sequenceStep = upper.sequence - lower.sequence
            val syntheticHeldBridge = gap > 0L && sequenceStep == 0 &&
                lower.hasSameChassisSample(upper)
            val ordinaryAcceptedStep = gap in 1L..MAX_INTERPOLATED_GAP && sequenceStep == 1
            if ((!syntheticHeldBridge && !ordinaryAcceptedStep) ||
                isSpatialDiscontinuity(lower, upper)
            ) return true
        }
        return false
    }

    private fun complete(
        pose: VehiclePoseSnapshot,
        target: Double,
        alpha: Float,
        lower: VehiclePoseSnapshot,
        upper: VehiclePoseSnapshot,
        mode: VehicleChassisPresentation.Mode,
    ): VehicleChassisPresentation {
        val anchor = pose.anchor ?: Vec3.ZERO
        return VehicleChassisPresentation(
            pose, anchor, pose.chassisYawDegrees, target, alpha,
            lower.sequence, upper.sequence, samples.size, mode,
        )
    }

    private fun finite(value: Vec3): Boolean = value.x.isFinite() && value.y.isFinite() && value.z.isFinite()

    companion object {
        const val OBSERVER_DELAY_TICKS = 2.0
        const val MAX_INTERPOLATED_GAP = 3L
        // Changed-only publication has no terminal velocity sample. Holding the newest complete
        // authority is the only bounded policy that cannot preserve an invented overshoot.
        const val MAX_EXTRAPOLATION_TICKS = 0.0
        const val POSITION_DEADBAND = 1.0 / 32.0
        const val YAW_DEADBAND = 1F
        const val SOFT_FACTOR = 0.25
        const val MAX_HORIZONTAL_CORRECTION = 0.10
        const val MAX_COMPOSED_Y_CORRECTION = 0.055
        const val MAX_YAW_CORRECTION = 2F
        const val HARD_POSITION_RESET = 1.5
        const val HARD_YAW_RESET = 30F
        private const val MAX_SAMPLES = 16
        private const val MAX_PREDICTIONS = 64
        private const val MAX_CLIENT_ADVANCE = 4

        fun isSpatialDiscontinuity(
            previous: VehiclePoseSnapshot,
            current: VehiclePoseSnapshot,
        ): Boolean {
            val previousAnchor = previous.anchor ?: return false
            val currentAnchor = current.anchor ?: return false
            return hypot(previousAnchor.x - currentAnchor.x, previousAnchor.z - currentAnchor.z) >
                    HARD_POSITION_RESET ||
                    abs((currentAnchor.y + current.verticalOffset) -
                        (previousAnchor.y + previous.verticalOffset)) > HARD_POSITION_RESET ||
                    abs(Mth.wrapDegrees(
                        current.chassisYawDegrees - previous.chassisYawDegrees
                    )) > HARD_YAW_RESET
        }
    }
}
