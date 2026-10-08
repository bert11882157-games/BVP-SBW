package com.atsuishio.superbwarfare.api.aircraft

import java.util.ArrayDeque
import kotlin.math.ceil

/** Server tick state machine; one emission is always a left/right pair. No entity or mod linkage. */
class AircraftCountermeasureState {
    data class Output(val flarePairs: Int, val flareLevel: Int, val chaffLevel: Int,
        val chaffEmitting: Boolean, val flareCooldown: Int, val chaffCooldown: Int,
        val chaffStarted: Boolean)

    private val flareExpiries = ArrayDeque<Long>()
    private var burstUsed = 0
    private var nextPairTick = 0.0
    var flareReadyAt = 0L
        private set
    var chaffReadyAt = 0L
        private set
    private var chaffStartedAt: Long? = null
    private var chaffProgramTicks = CHAFF_RELEASE_TICKS
    private var pairRollback: Pair<Int, Long>? = null

    fun tick(now: Long, flareHeld: Boolean, chaffHeld: Boolean, flaresEnabled: Boolean,
        chaffEnabled: Boolean, flaresPerSecond: Int, flaresPerBurst: Int,
        chaffReleaseTicks: Int = CHAFF_RELEASE_TICKS): Output {
        require(chaffReleaseTicks in 10..MAX_CHAFF_RELEASE_TICKS && chaffReleaseTicks % 10 == 0)
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
                flareReadyAt = now + FLARE_COOLDOWN_TICKS
            }
        }
        val chaffStarted = chaffEnabled && chaffHeld && now >= chaffReadyAt
        if (chaffStarted) {
            chaffStartedAt = now
            chaffProgramTicks = chaffReleaseTicks
            chaffReadyAt = now + chaffProgramTicks + COOLDOWN_TICKS
        }
        val elapsed = chaffStartedAt?.let { now - it }
        val chaffLevel = if (chaffEnabled && elapsed != null) chaffLevelAt(elapsed, chaffProgramTicks) else 0
        if (elapsed != null && elapsed >= chaffProgramTicks + CHAFF_DECAY_TICKS) chaffStartedAt = null
        return Output(pairs, flareExpiries.size, chaffLevel,
            chaffEnabled && elapsed != null && elapsed in 0..chaffProgramTicks.toLong(),
            (flareReadyAt - now).coerceIn(0, FLARE_COOLDOWN_TICKS.toLong()).toInt(),
            (chaffReadyAt - now).coerceIn(0, COOLDOWN_TICKS.toLong()).toInt(), chaffStarted)
    }

    /** Temporary clouds/decoys do not survive unload; cooldowns and partial burst expenditure do. */
    fun restore(now: Long, flareReady: Long, chaffReady: Long, used: Int) {
        flareExpiries.clear(); chaffStartedAt = null
        flareReadyAt = flareReady.coerceIn(now, now + FLARE_COOLDOWN_TICKS)
        chaffReadyAt = chaffReady.coerceIn(now, now + MAX_CHAFF_RELEASE_TICKS + COOLDOWN_TICKS)
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
        const val MAX_CHAFF_RELEASE_TICKS = 1200
        const val CHAFF_DECAY_TICKS = 60
        const val COOLDOWN_TICKS = 400
        /** Flare burst cooldown: half the chaff cooldown (owner 2026-10-08: flares fire twice as often). */
        const val FLARE_COOLDOWN_TICKS = 200
        fun chaffLevelAt(elapsed: Long, releaseTicks: Int = CHAFF_RELEASE_TICKS): Int {
            require(releaseTicks in 10..MAX_CHAFF_RELEASE_TICKS && releaseTicks % 10 == 0)
            if (elapsed < 0 || elapsed >= releaseTicks + CHAFF_DECAY_TICKS) return 0
            fun accumulated(ticks: Long): Int {
                val steps = (ticks / 10).toInt()
                return minOf(steps, 2) + (steps - 2).coerceIn(0, 2) * 2 +
                    (steps - 4).coerceAtLeast(0) * 3
            }
            return if (elapsed <= releaseTicks) accumulated(elapsed) else
                ceil(accumulated(releaseTicks.toLong()) *
                    (releaseTicks + CHAFF_DECAY_TICKS - elapsed).toDouble() / CHAFF_DECAY_TICKS).toInt()
        }
    }
}
