package com.atsuishio.superbwarfare.api.vehicle.aim

import net.minecraft.util.Mth
import java.util.EnumMap

/** Immutable authoritative aim sample resolved for one presentation time. */
data class VehicleAimPresentationSample(
    val channel: VehicleAimChannel,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val mode: VehicleAimMode,
    val actualYaw: Float,
    val actualPitch: Float,
    /** Presentation query time; it may be newer than the last proven authoritative sample. */
    val presentationServerTick: Double,
    /** Real authoritative/interpolated sample time. Never rewritten to a sparse-tail query time. */
    val sourceServerTick: Double,
    /** True when changed packet timing is holding a prior authoritative sample at a later time. */
    val tailHeld: Boolean,
    /** True only for a bounded compatible identity rekey of one immutable physical channel. */
    val contextRebound: Boolean,
    val lowerSequence: Int,
    val upperSequence: Int,
    val alpha: Float,
    val lockedDiagnostic: Boolean,
)

/**
 * Client-only bounded history for authoritative actual aim. It owns no entity fields and performs
 * no prediction: sparse changed-only tails hold, while unknown gaps fail closed.
 */
internal class VehicleAimPresentationTimeline {
    private data class ChannelHistory(
        var seatIndex: Int = -1,
        var selectedWeaponIndex: Int = -1,
        var mode: VehicleAimMode = VehicleAimMode.INACTIVE,
        var lastOfferClientTick: Int = Int.MIN_VALUE,
        var contextRebound: Boolean = false,
        val samples: ArrayList<VehicleAimSnapshot> = ArrayList(MAX_SAMPLES),
        /** Immutable client-tick receipt for each accepted sample, aligned with [samples]. */
        val receiptClientTicks: ArrayList<Double> = ArrayList(MAX_SAMPLES),
    ) {
    }

    private val histories = EnumMap<VehicleAimChannel, ChannelHistory>(VehicleAimChannel::class.java)
    var epoch: Int = 0
        private set

    fun clear() {
        histories.clear()
        epoch += 1
    }

    fun retainChannels(activeChannels: Set<VehicleAimChannel>) {
        if (histories.keys.removeIf { it !in activeChannels }) epoch += 1
    }

    /**
     * Admission preflight used before any compatible identity rekey. A delayed old payload must
     * never rewrite the current physical channel identity and only then be rejected by [offer].
     */
    fun canAccept(snapshot: VehicleAimSnapshot): Boolean {
        if (!finite(snapshot)) return false
        val history = histories[snapshot.channel] ?: return true
        return isAcceptable(history, snapshot)
    }

    fun offer(
        snapshot: VehicleAimSnapshot,
        clientTick: Int,
        receiptPartialTicks: Float = 0F,
    ): Boolean {
        if (!finite(snapshot)) return false
        val receiptClientTime = clientTick.toDouble() + receiptPartialTicks.coerceIn(0F, 1F).toDouble()
        if (!receiptClientTime.isFinite()) return false
        val history = histories.getOrPut(snapshot.channel) { ChannelHistory() }
        // Sequence/tick order belongs to the physical channel, not its selected-weapon identity.
        // Reject a delayed pre-transition payload before it can clear a newer identity/history.
        // A newer identity baseline may legitimately share the current server tick (including
        // same-tick A-B-A); an unchanged identity still requires a strictly newer tick.
        if (!isAcceptable(history, snapshot)) return false
        val identityChanged = history.seatIndex != snapshot.seatIndex ||
            history.selectedWeaponIndex != snapshot.selectedWeaponIndex ||
            history.mode != snapshot.mode
        if (identityChanged) {
            history.samples.clear()
            history.receiptClientTicks.clear()
            epoch += 1
            history.seatIndex = snapshot.seatIndex
            history.selectedWeaponIndex = snapshot.selectedWeaponIndex
            history.mode = snapshot.mode
        }
        history.samples.add(snapshot)
        // Keep the actual render/tick phase at which the complete authoritative payload became
        // visible. Integer-only receipts quantize a late packet to the next tick and make a
        // consecutive actual sample appear as an endpoint step instead of a bounded blend.
        history.receiptClientTicks.add(receiptClientTime)
        history.lastOfferClientTick = clientTick
        history.contextRebound = false
        while (history.samples.size > MAX_SAMPLES) {
            history.samples.removeAt(0)
            history.receiptClientTicks.removeAt(0)
        }
        return true
    }

    private fun isAcceptable(history: ChannelHistory, snapshot: VehicleAimSnapshot): Boolean {
        val previous = history.samples.lastOrNull() ?: return true
        val identityChanged = history.seatIndex != snapshot.seatIndex ||
            history.selectedWeaponIndex != snapshot.selectedWeaponIndex ||
            history.mode != snapshot.mode
        return VehicleAimMath.isNewerSequence(snapshot.sequence, previous.sequence) &&
            snapshot.serverTick >= previous.serverTick &&
            (identityChanged || history.contextRebound || snapshot.serverTick != previous.serverTick)
    }

    /**
     * Atomically rekeys the last immutable physical actual to an independently synchronized,
     * mechanically compatible selected-weapon identity. The original source/receipt age is
     * retained; target and lock claims are revoked. No entity axis or command state is read.
     */
    fun rebindCompatibleContext(
        channel: VehicleAimChannel,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        mode: VehicleAimMode,
        clientTick: Int,
        compatible: (previousWeaponIndex: Int) -> Boolean,
    ): Boolean {
        val history = histories[channel] ?: return false
        if (history.seatIndex != seatIndex || history.samples.isEmpty()) return false
        if (history.selectedWeaponIndex == selectedWeaponIndex && history.mode == mode) return true
        val clientAge = clientTick - history.lastOfferClientTick
        if (clientAge !in 0..MAX_CONTEXT_REBIND_TICKS ||
            (history.selectedWeaponIndex != selectedWeaponIndex &&
                !compatible(history.selectedWeaponIndex))
        ) {
            return false
        }
        val rebound = history.samples.map { physical ->
            physical.copy(
                seatIndex = seatIndex,
                selectedWeaponIndex = selectedWeaponIndex,
                mode = mode,
                targetYaw = physical.actualYaw,
                targetPitch = physical.actualPitch,
                locked = false,
            )
        }
        rebound.forEachIndexed { index, sample -> history.samples[index] = sample }
        history.selectedWeaponIndex = selectedWeaponIndex
        history.mode = mode
        history.contextRebound = true
        epoch += 1
        return true
    }

    /**
     * Revokes controller-bound diagnostics without discarding the vehicle-owned physical actual.
     * The retained sample keeps its original receipt age and therefore expires under the same
     * bounded transition lease unless a fresh authoritative baseline replaces it.
     */
    fun beginControllerEpoch(changedChannels: Set<VehicleAimChannel>) {
        epoch += 1
        changedChannels.forEach { channel ->
            val history = histories[channel] ?: return@forEach
            val physicalIndex = history.samples.lastIndex
            val physical = history.samples.getOrNull(physicalIndex) ?: return@forEach
            val physicalReceipt = history.receiptClientTicks.getOrNull(physicalIndex)
                ?: history.lastOfferClientTick.toDouble()
            history.samples.clear()
            history.receiptClientTicks.clear()
            history.samples.add(
                physical.copy(
                    mode = VehicleAimMode.INACTIVE,
                    targetYaw = physical.actualYaw,
                    targetPitch = physical.actualPitch,
                    locked = false,
                ),
            )
            history.receiptClientTicks.add(physicalReceipt)
            history.mode = VehicleAimMode.INACTIVE
            history.contextRebound = true
        }
    }

    fun resolveAt(
        channel: VehicleAimChannel,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        serverTick: Double,
    ): VehicleAimPresentationSample? {
        if (!serverTick.isFinite()) return null
        val history = histories[channel]?.takeIf {
            it.seatIndex == seatIndex && it.selectedWeaponIndex == selectedWeaponIndex
        } ?: return null
        val samples = history.samples
        val first = samples.firstOrNull() ?: return null
        if (serverTick < first.serverTick.toDouble()) {
            // Aim is event-bound hull-local state. A changed-only chassis clock can legitimately
            // predate the retained aim window while stationary; use the newest exact-context
            // physical state without inventing a source tick or a lock claim.
            return complete(
                samples.last(),
                serverTick,
                tailHeld = true,
                contextRebound = history.contextRebound,
            ).copy(lockedDiagnostic = false)
        }
        for (index in 1 until samples.size) {
            val lower = samples[index - 1]
            val upper = samples[index]
            if (serverTick > upper.serverTick.toDouble()) continue
            val tickGap = upper.serverTick - lower.serverTick
            if (tickGap <= 0L || !VehicleAimMath.isNewerSequence(upper.sequence, lower.sequence)) return null
            // Unlike changed-only chassis state, active aim publishes every authoritative tick.
            // Any tick gap is loss/coalescing: hold the last proven axis, then accept the exact
            // upper baseline without interpolating an unknown interior path.
            if (tickGap != 1L) {
                return if (serverTick < upper.serverTick.toDouble()) held(lower, serverTick)
                else complete(upper, serverTick, tailHeld = false, contextRebound = history.contextRebound)
            }
            val alpha = (serverTick - lower.serverTick.toDouble()).toFloat().coerceIn(0F, 1F)
            return interpolate(lower, upper, alpha, serverTick, serverTick)
        }
        val newest = samples.last()
        val age = serverTick - newest.serverTick.toDouble()
        // Active ground aim is an event-bound hull-local physical state. Once accepted into this
        // exact channel/seat/weapon/mode history it remains current until a newer sample or an
        // explicit context/epoch lifecycle event replaces or clears it.
        if (age < -TIME_EPSILON) return null
        return complete(
            newest,
            serverTick,
            tailHeld = age > TIME_EPSILON,
            contextRebound = history.contextRebound,
        )
    }

    fun resolveChannelAt(
        channel: VehicleAimChannel,
        serverTick: Double,
    ): VehicleAimPresentationSample? {
        val history = histories[channel] ?: return null
        return resolveAt(channel, history.seatIndex, history.selectedWeaponIndex, serverTick)
    }

    /**
     * Receipt-relative blend for local/providerless presentation. The receipt timestamp is written
     * only by [offer], so this function is pure with respect to callers: render, camera, HUD, and
     * CCIP queries cannot restart or advance one another's clock. Each accepted upper sample blends
     * from the immediately preceding authoritative upper sample and reaches it after one client
     * tick; no caller-order feedback or extrapolation is possible.
     */
    fun resolveLatest(
        channel: VehicleAimChannel,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        partialTicks: Float,
        clientTick: Int,
        presentationServerTick: Double,
    ): VehicleAimPresentationSample? {
        if (!presentationServerTick.isFinite()) return null
        val history = histories[channel]?.takeIf {
            it.seatIndex == seatIndex && it.selectedWeaponIndex == selectedWeaponIndex
        } ?: return null
        val upperIndex = history.samples.lastIndex
        val upper = history.samples.getOrNull(upperIndex) ?: return null
        val clientAge = clientTick - history.lastOfferClientTick
        if (clientAge < 0) return null
        val lowerIndex = upperIndex - 1
        val lower = history.samples.getOrNull(lowerIndex)
        val now = clientTick.toDouble() + partialTicks.coerceIn(0F, 1F).toDouble()
        val receipt = history.receiptClientTicks.getOrNull(upperIndex)
            ?: history.lastOfferClientTick.toDouble()
        if (!receipt.isFinite()) return null
        val tickGap = lower?.let { upper.serverTick - it.serverTick }
        if (lower == null || tickGap != 1L ||
            !VehicleAimMath.isNewerSequence(upper.sequence, lower.sequence)
        ) {
            // Coalesced/lost interiors remain an exact upper baseline. Do not invent a path or
            // let the query order turn this into an implicit smoothing state.
            return complete(upper, presentationServerTick, tailHeld = false, contextRebound = history.contextRebound)
        }
        val elapsed = (now - receipt).coerceIn(0.0, RECEIPT_BLEND_TICKS)
        if (elapsed >= RECEIPT_BLEND_TICKS - TIME_EPSILON) {
            return complete(upper, presentationServerTick, tailHeld = false, contextRebound = history.contextRebound)
        }
        return receiptBlend(
            lower,
            upper,
            elapsed.toFloat(),
            presentationServerTick,
            history.contextRebound,
        )
    }

    fun resolveLatestChannel(
        channel: VehicleAimChannel,
        partialTicks: Float,
        clientTick: Int,
        presentationServerTick: Double,
    ): VehicleAimPresentationSample? {
        val history = histories[channel] ?: return null
        return resolveLatest(
            channel,
            history.seatIndex,
            history.selectedWeaponIndex,
            partialTicks,
            clientTick,
            presentationServerTick,
        )
    }

    private fun interpolate(
        lower: VehicleAimSnapshot,
        upper: VehicleAimSnapshot,
        alpha: Float,
        presentationServerTick: Double,
        sourceServerTick: Double,
    ): VehicleAimPresentationSample {
        val yaw = lower.actualYaw + Mth.wrapDegrees(upper.actualYaw - lower.actualYaw) * alpha
        val pitch = Mth.lerp(alpha, lower.actualPitch, upper.actualPitch)
        return VehicleAimPresentationSample(
            upper.channel,
            upper.seatIndex,
            upper.selectedWeaponIndex,
            upper.mode,
            yaw,
            pitch,
            presentationServerTick,
            sourceServerTick,
            false,
            false,
            lower.sequence,
            upper.sequence,
            alpha,
            if (alpha >= 1F) upper.locked else lower.locked,
        )
    }

    private fun receiptBlend(
        lower: VehicleAimSnapshot,
        upper: VehicleAimSnapshot,
        alpha: Float,
        presentationServerTick: Double,
        contextRebound: Boolean,
    ): VehicleAimPresentationSample {
        val yaw = lower.actualYaw + Mth.wrapDegrees(upper.actualYaw - lower.actualYaw) * alpha
        val pitch = Mth.lerp(alpha, lower.actualPitch, upper.actualPitch)
        return VehicleAimPresentationSample(
            upper.channel,
            upper.seatIndex,
            upper.selectedWeaponIndex,
            upper.mode,
            yaw,
            pitch,
            presentationServerTick,
            upper.serverTick.toDouble(),
            true,
            contextRebound,
            lower.sequence,
            upper.sequence,
            alpha,
            false,
        )
    }

    private fun held(snapshot: VehicleAimSnapshot, presentationServerTick: Double): VehicleAimPresentationSample? {
        val age = presentationServerTick - snapshot.serverTick.toDouble()
        if (age < -TIME_EPSILON) return null
        return complete(
            snapshot,
            presentationServerTick,
            tailHeld = age > TIME_EPSILON,
            contextRebound = false,
        )
    }

    private fun complete(
        snapshot: VehicleAimSnapshot,
        presentationServerTick: Double,
        tailHeld: Boolean,
        contextRebound: Boolean,
    ) = VehicleAimPresentationSample(
        snapshot.channel,
        snapshot.seatIndex,
        snapshot.selectedWeaponIndex,
        snapshot.mode,
        snapshot.actualYaw,
        snapshot.actualPitch,
        presentationServerTick,
        snapshot.serverTick.toDouble(),
        tailHeld,
        contextRebound,
        snapshot.sequence,
        snapshot.sequence,
        1F,
        snapshot.locked,
    )

    private fun finite(snapshot: VehicleAimSnapshot): Boolean =
        snapshot.seatIndex >= 0 && snapshot.selectedWeaponIndex >= 0 && snapshot.serverTick >= 0L &&
            snapshot.targetYaw.isFinite() && snapshot.targetPitch.isFinite() &&
            snapshot.actualYaw.isFinite() && snapshot.actualPitch.isFinite()

    companion object {
        const val MAX_SAMPLES = 16
        const val MAX_CONTEXT_REBIND_TICKS = 20
        private const val RECEIPT_BLEND_TICKS = 1.0
        private const val TIME_EPSILON = 1.0E-6
    }
}
