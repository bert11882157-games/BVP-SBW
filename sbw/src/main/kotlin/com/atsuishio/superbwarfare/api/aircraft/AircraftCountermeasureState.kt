package com.atsuishio.superbwarfare.api.aircraft

import java.util.ArrayDeque
import kotlin.math.ceil

/** Server tick state machine; one emission is always a left/right pair. No entity or mod linkage. */
class AircraftCountermeasureState {
    data class Output(val flarePairs: Int, val flareLevel: Int, val chaffLevel: Int,
        val chaffEmitting: Boolean, val flareCooldown: Int, val chaffCooldown: Int)

    private val flareExpiries = ArrayDeque<Long>()
    private var burstUsed = 0
    private var nextPairTick = 0.0
    var flareReadyAt = 0L
        private set
    var chaffReadyAt = 0L
        private set
    private var chaffStartedAt: Long? = null
    private var pairRollback: Pair<Int, Long>? = null

    fun tick(now: Long, flareHeld: Boolean, chaffHeld: Boolean, flaresEnabled: Boolean,
        chaffEnabled: Boolean, flaresPerSecond: Int, flaresPerBurst: Int): Output {
        while (flareExpiries.isNotEmpty() && flareExpiries.first <= now) flareExpiries.removeFirst()
        pairRollback = null
        var pairs = 0
        if (flaresEnabled && flareHeld && now >= flareReadyAt && now.toDouble() >= nextPairTick) {
            pairs = 1
            pairRollback = burstUsed to flareReadyAt
            flareExpiries.addLast(now + FLARE_LIFETIME_TICKS)
            flareExpiries.addLast(now + FLARE_LIFETIME_TICKS)
            burstUsed += 2
            // Preserve the fractional cadence while held; no catch-up bursts after a pause.
            nextPairTick = (if (now - nextPairTick >= 1.0) now.toDouble() else nextPairTick) +
                40.0 / flaresPerSecond.coerceIn(2, 40)
            if (burstUsed >= flaresPerBurst.coerceIn(2, 128)) {
                burstUsed = 0
                flareReadyAt = now + COOLDOWN_TICKS
            }
        }
        if (chaffEnabled && chaffHeld && now >= chaffReadyAt) {
            chaffStartedAt = now
            chaffReadyAt = now + CHAFF_RELEASE_TICKS + COOLDOWN_TICKS
        }
        val elapsed = chaffStartedAt?.let { now - it }
        val chaffLevel = if (chaffEnabled && elapsed != null) chaffLevelAt(elapsed) else 0
        if (elapsed != null && elapsed >= 120) chaffStartedAt = null
        return Output(pairs, flareExpiries.size, chaffLevel,
            chaffEnabled && elapsed != null && elapsed in 0..60,
            (flareReadyAt - now).coerceIn(0, COOLDOWN_TICKS.toLong()).toInt(),
            (chaffReadyAt - now).coerceIn(0, COOLDOWN_TICKS.toLong()).toInt())
    }

    /** Temporary clouds/decoys do not survive unload; cooldowns and partial burst expenditure do. */
    fun restore(now: Long, flareReady: Long, chaffReady: Long, used: Int) {
        flareExpiries.clear(); chaffStartedAt = null
        flareReadyAt = flareReady.coerceIn(now, now + COOLDOWN_TICKS)
        chaffReadyAt = chaffReady.coerceIn(now, now + CHAFF_RELEASE_TICKS + COOLDOWN_TICKS)
        burstUsed = used.coerceIn(0, 126)
        nextPairTick = now + 1.0
    }

    fun burstExpenditure(): Int = burstUsed

    /** A canceled entity-spawn pair never contributes levels or consumes a burst. */
    fun abortPair() {
        val previous = pairRollback ?: return
        pairRollback = null
        flareExpiries.removeLast(); flareExpiries.removeLast()
        burstUsed = previous.first
        flareReadyAt = previous.second
    }

    companion object {
        const val FLARE_LIFETIME_TICKS = 40
        const val CHAFF_RELEASE_TICKS = 60
        const val COOLDOWN_TICKS = 400
        fun chaffLevelAt(elapsed: Long): Int = when {
            elapsed < 0 || elapsed >= 120 -> 0
            elapsed <= 60 -> (1..minOf(6, (elapsed / 10).toInt())).sumOf { (it + 1) / 2 }
            else -> ceil(12.0 * (120 - elapsed) / 60.0).toInt()
        }
    }
}
