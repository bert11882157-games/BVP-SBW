package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Client copy of a server-steered munition's flight (guided missiles, guided bombs, accelerating rockets).
 *
 * The client cannot fly these itself: guidance, seekers and motors run on the server only. Instead of predicting
 * and snapping to late corrections, the client keeps a short buffer of authoritative states, one per server tick,
 * and plays them back a little behind the newest one. The delay absorbs packets that arrive in bunches or late, so
 * the drawn path is the server's path with a continuous position and heading.
 *
 * Times are server ticks ([Sample.stamp], from the server's tick counter, not the entity age). Positions are world
 * coordinates in blocks; velocities are blocks per tick. Client thread only; one instance per entity.
 */
class MunitionFlightPlayback {
    /** One authoritative state at the end of server tick [stamp]. Angles in degrees, as the entity stores them. */
    data class Sample(val stamp: Int, val position: Vec3, val velocity: Vec3, val yRot: Float, val xRot: Float)

    /** What the entity shows on this client tick. [velocity] is the path tangent in blocks per tick. */
    data class State(val position: Vec3, val velocity: Vec3, val yRot: Float, val xRot: Float)

    private val samples = ArrayList<Sample>(CAPACITY + 1)
    /** Playback time in server ticks; NaN until the first sample. */
    private var clock = Double.NaN
    /** Newest stamp minus local ticks, per tick: constant while states arrive evenly; its spread is the jitter. */
    private val offsets = IntArray(JITTER_WINDOW_TICKS)
    private var offsetCount = 0
    private var localTicks = 0

    /** True once there is a state to show; until then the entity keeps its vanilla client behaviour. */
    fun active(): Boolean = samples.isNotEmpty()

    /** Current playback delay behind the newest sample, in ticks. */
    fun targetDelayTicks(): Double {
        val newest = samples.lastOrNull() ?: return BASE_DELAY_TICKS
        return newest.stamp - targetTime()
    }

    /**
     * Where playback should be, in server ticks. It follows the latest-arriving stream of the recent window (the
     * smallest newest-stamp-minus-local-tick offset) less [BASE_DELAY_TICKS], so a state that arrives as late as
     * any recent one is still in the buffer when it is due. That offset is steady under jitter; following the
     * newest stamp instead would jump with every burst. The delay behind the best-case arrival is capped at
     * [MAX_DELAY_TICKS].
     */
    private fun targetTime(): Double {
        if (offsetCount == 0) return (samples.lastOrNull()?.stamp ?: 0) - BASE_DELAY_TICKS
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        for (i in 0 until offsetCount) {
            low = minOf(low, offsets[i]); high = maxOf(high, offsets[i])
        }
        return localTicks + maxOf(low - BASE_DELAY_TICKS, high - MAX_DELAY_TICKS)
    }

    /**
     * Adds an authoritative state. Stale (older than the buffer), duplicate and non-finite states are ignored and
     * return false. A state after a long gap (a residency pause, a teleport) restarts playback from it.
     */
    fun offer(sample: Sample): Boolean {
        if (!finite(sample.position) || !finite(sample.velocity) || !sample.yRot.isFinite() || !sample.xRot.isFinite())
            return false
        val newest = samples.lastOrNull()
        if (newest != null) {
            if (newest.stamp - sample.stamp >= CAPACITY) return false
            if (sample.stamp - newest.stamp > RESYNC_TICKS) {
                reset()
            } else if (sample.stamp <= newest.stamp) {
                val index = samples.indexOfFirst { it.stamp >= sample.stamp }
                if (index < 0 || samples[index].stamp == sample.stamp) return false
                if (index == 0 && samples.size >= CAPACITY) return false
                samples.add(index, sample)
                trim()
                return true
            }
        }
        samples.add(sample)
        trim()
        return true
    }

    /**
     * Advances playback by one client tick and returns the state to show, or null while nothing has arrived.
     * The clock runs at one server tick per client tick and slews by at most [MAX_SLEW] per tick toward
     * [targetTime]. It jumps forward only when it is more than [RESYNC_TICKS] behind, and it never runs more than
     * [MAX_EXTRAPOLATION_TICKS] past the newest state, so a stall never makes the munition jump back along its path.
     */
    fun advance(): State? {
        val newest = samples.lastOrNull() ?: return null
        localTicks++
        recordOffset(newest.stamp - localTicks)
        val target = targetTime()
        val next = clock + 1.0
        clock = if (clock.isNaN() || target - next > RESYNC_TICKS) target
            else minOf(next + ((target - next) * SLEW_GAIN).coerceIn(-MAX_SLEW, MAX_SLEW),
                newest.stamp + MAX_EXTRAPOLATION_TICKS)
        return sample(clock)
    }

    /** The flight at server time [time]: Hermite through the buffered positions, held or briefly extrapolated at the ends. */
    fun sample(time: Double): State? {
        if (samples.isEmpty() || time.isNaN()) return null
        val first = samples.first()
        val last = samples.last()
        if (time <= first.stamp) return State(first.position, tangent(0), first.yRot, first.xRot)
        if (time >= last.stamp) {
            // Starved: continue along the last velocity for a moment, then hold, rather than freeze and leap.
            val ahead = (time - last.stamp).coerceAtMost(MAX_EXTRAPOLATION_TICKS)
            return State(last.position.add(last.velocity.scale(ahead)), last.velocity, last.yRot, last.xRot)
        }
        var i = samples.size - 2
        while (i > 0 && samples[i].stamp > time) i--
        val a = samples[i]
        val b = samples[i + 1]
        val span = (b.stamp - a.stamp).toDouble()
        val u = ((time - a.stamp) / span).coerceIn(0.0, 1.0)
        val yRot = Mth.rotLerp(u.toFloat(), a.yRot, b.yRot)
        val xRot = Mth.lerp(u.toFloat(), a.xRot, b.xRot)
        if (span > MAX_CURVE_SPAN_TICKS) {
            // A long hole in the stream: a straight line between its ends, no invented curve.
            val chord = b.position.subtract(a.position)
            return State(a.position.add(chord.scale(u)), chord.scale(1.0 / span), yRot, xRot)
        }
        val m0 = tangent(i).scale(span)
        val m1 = tangent(i + 1).scale(span)
        val u2 = u * u
        val u3 = u2 * u
        val position = a.position.scale(2 * u3 - 3 * u2 + 1)
            .add(m0.scale(u3 - 2 * u2 + u))
            .add(b.position.scale(-2 * u3 + 3 * u2))
            .add(m1.scale(u3 - u2))
        val velocity = a.position.scale(6 * u2 - 6 * u)
            .add(m0.scale(3 * u2 - 4 * u + 1))
            .add(b.position.scale(-6 * u2 + 6 * u))
            .add(m1.scale(3 * u2 - 2 * u))
            .scale(1.0 / span)
        return State(position, velocity, yRot, xRot)
    }

    fun reset() {
        samples.clear()
        clock = Double.NaN
        offsetCount = 0
        localTicks = 0
    }

    /**
     * Path tangent at buffered sample [index] in blocks per tick: the central difference of its neighbours'
     * positions (Catmull-Rom), one-sided at the ends. Positions, not the sent velocity, so a collision or a
     * guidance turn inside the tick cannot make the curve overshoot.
     */
    private fun tangent(index: Int): Vec3 {
        if (samples.size < 2) return samples[index].velocity
        val before = samples[maxOf(0, index - 1)]
        val after = samples[minOf(samples.size - 1, index + 1)]
        val span = (after.stamp - before.stamp).toDouble()
        return after.position.subtract(before.position).scale(1.0 / span)
    }

    private fun trim() {
        while (samples.size > CAPACITY) samples.removeAt(0)
        // Samples already behind the clock are kept only as the left neighbour of the current segment.
        while (samples.size > 3 && !clock.isNaN() && samples[2].stamp < clock - 1.0) samples.removeAt(0)
    }

    private fun recordOffset(offset: Int) {
        if (offsetCount == JITTER_WINDOW_TICKS) {
            System.arraycopy(offsets, 1, offsets, 0, JITTER_WINDOW_TICKS - 1)
            offsetCount--
        }
        offsets[offsetCount++] = offset
    }

    companion object {
        /** States kept; well over the largest delay so a late burst still finds its neighbours. */
        const val CAPACITY = 24
        /** Delay behind the newest state with perfectly even arrival. */
        const val BASE_DELAY_TICKS = 1.0
        /** Upper bound of the adaptive delay; also bounds how far behind the server the seeker view can be. */
        const val MAX_DELAY_TICKS = 4.0
        /** Ticks of arrival history used to measure jitter. */
        const val JITTER_WINDOW_TICKS = 100
        /** A stream gap, or a clock lag, longer than this restarts playback instead of slewing. */
        const val RESYNC_TICKS = 8
        /** Clock-rate correction per tick of error, and its bound: 5% speed changes are not visible. */
        const val SLEW_GAIN = 0.05
        const val MAX_SLEW = 0.05
        /** Starved playback extrapolates at most this far past the newest state. */
        const val MAX_EXTRAPOLATION_TICKS = 2.0
        /** Gaps longer than this are drawn straight. */
        const val MAX_CURVE_SPAN_TICKS = 4.0

        private fun finite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
    }
}
