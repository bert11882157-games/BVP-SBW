package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MunitionFlightPlaybackTest {
    /** A guided missile's server path: 4 blocks/tick, a 6 degree/tick turn from tick 20 to 50, the corkscrew. */
    private fun serverPath(ticks: Int): List<MunitionFlightPlayback.Sample> {
        val out = ArrayList<MunitionFlightPlayback.Sample>()
        var position = Vec3(0.0, 120.0, 0.0)
        var heading = 0.0
        for (tick in 0 until ticks) {
            if (tick in 20 until 50) heading += Math.toRadians(6.0)
            val clean = Vec3(sin(heading), -0.05, cos(heading)).normalize().scale(4.0)
            val velocity = GuidedMissileGuidance.spinPerturbation(clean, Vec3.ZERO, tick, 6.0)
            out += MunitionFlightPlayback.Sample(1000 + tick, position, velocity,
                Math.toDegrees(kotlin.math.atan2(-velocity.x, velocity.z)).toFloat(),
                Math.toDegrees(kotlin.math.atan2(-velocity.y, velocity.horizontalDistance())).toFloat())
            position = position.add(velocity)
        }
        return out
    }

    /** Client tick on which each server tick's state arrives: 3 ticks of latency plus 0-2 ticks of jitter. */
    private fun arrivals(count: Int, seed: Long, jitter: Int): IntArray {
        val random = java.util.Random(seed)
        var last = 0
        return IntArray(count) { i ->
            // A link delivers in order; a late packet holds the ones behind it (they then arrive in a burst).
            last = maxOf(last, i + 3 + if (jitter > 0) random.nextInt(jitter + 1) else 0)
            last
        }
    }

    /** Positions the client shows at the end of each client tick. */
    private fun playback(path: List<MunitionFlightPlayback.Sample>, arrive: IntArray, ticks: Int): List<Vec3> {
        val playback = MunitionFlightPlayback()
        val shown = ArrayList<Vec3>()
        for (clientTick in 0 until ticks) {
            path.indices.filter { arrive[it] == clientTick }.forEach { playback.offer(path[it]) }
            playback.advance()?.let { shown += it.position }
        }
        return shown
    }

    /**
     * The scheme guided missiles used before: the client flies its own (unguided) step, then each state is aligned
     * to the client's step count and its error blended in over three ticks (BallisticSync).
     */
    private fun predictCorrect(path: List<MunitionFlightPlayback.Sample>, arrive: IntArray, ticks: Int): List<Vec3> {
        val correction = BallisticSync.Correction()
        var position = path[0].position
        var velocity = path[0].velocity
        var clientAge = 0
        val shown = ArrayList<Vec3>()
        for (clientTick in 0 until ticks) {
            position = position.add(velocity)
            clientAge++
            correction.next()?.let { position = position.add(it) }
            for (i in path.indices.filter { arrive[it] == clientTick }) {
                val s = path[i]
                val aligned = BallisticSync.align(i + 1, clientAge, s.position.add(s.velocity), s.velocity, { it }, { it })
                if (aligned == null) { position = s.position; velocity = s.velocity; correction.clear(); continue }
                velocity = aligned.second
                if (!correction.offer(aligned.first.subtract(position), velocity.length())) {
                    position = aligned.first; correction.clear()
                }
            }
            if (clientTick >= arrive[0]) shown += position
        }
        return shown
    }

    /** The vanilla tracker path native SBW missiles used: snap to each tracker position, then the client step. */
    private fun snapToTracker(path: List<MunitionFlightPlayback.Sample>, arrive: IntArray, ticks: Int): List<Vec3> {
        var position = path[0].position
        var velocity = path[0].velocity
        val shown = ArrayList<Vec3>()
        for (clientTick in 0 until ticks) {
            path.indices.filter { arrive[it] == clientTick }.forEach {
                position = path[it].position.add(path[it].velocity); velocity = path[it].velocity
            }
            position = position.add(velocity)
            if (clientTick >= arrive[0]) shown += position
        }
        return shown
    }

    /**
     * RMS change of the per-tick step: what the eye reads as stutter (a steady turn contributes its curvature,
     * 0.42 blocks/tick here, to every scheme). Ticks 8-100 only: settled, and before the server stream ends.
     */
    private fun stutter(shown: List<Vec3>): Double {
        var sum = 0.0
        var n = 0
        for (i in 8 until minOf(shown.size - 1, 100)) {
            val a = shown[i].subtract(shown[i - 1])
            val b = shown[i + 1].subtract(shown[i])
            sum += b.subtract(a).lengthSqr(); n++
        }
        return sqrt(sum / n)
    }

    private fun maxStepChange(shown: List<Vec3>): Double = (8 until minOf(shown.size - 1, 100)).maxOf {
        shown[it + 1].subtract(shown[it]).subtract(shown[it].subtract(shown[it - 1])).length()
    }

    @Test fun `even arrival plays the server path back exactly, one tick behind`() {
        val path = serverPath(80)
        val playback = MunitionFlightPlayback()
        for (clientTick in 0 until 70) {
            playback.offer(path[clientTick])
            val state = playback.advance()!!
            if (clientTick >= 2) {
                assertEquals(0.0, state.position.distanceTo(path[clientTick - 1].position), 1.0E-6, "tick $clientTick")
            }
        }
    }

    @Test fun `jittered arrival stays as smooth as the real path and much smoother than the old schemes`() {
        val path = serverPath(120)
        val truth = stutter(path.map { it.position })
        val report = StringBuilder("stutter (RMS step change, blocks/tick); true path ${"%.3f".format(truth)}\n")
        for (seed in 1L..20L) {
            val arrive = arrivals(path.size, seed, jitter = 2)
            val new = playback(path, arrive, 130)
            val old = predictCorrect(path, arrive, 130)
            val tracker = snapToTracker(path, arrive, 130)
            if (seed <= 3) report.append("seed $seed: playback ${"%.3f".format(stutter(new))} " +
                "(max ${"%.2f".format(maxStepChange(new))}), predict+correct ${"%.3f".format(stutter(old))} " +
                "(max ${"%.2f".format(maxStepChange(old))}), tracker snap ${"%.3f".format(stutter(tracker))} " +
                "(max ${"%.2f".format(maxStepChange(tracker))})\n")
            // Playback is as smooth as the server's own path; the old schemes add stutter on top of it.
            assertTrue(stutter(new) < truth * 1.15 + 0.02, "seed $seed playback ${stutter(new)} vs true $truth")
            assertTrue(stutter(new) * 2 < stutter(old), "seed $seed playback ${stutter(new)} vs predict ${stutter(old)}")
            assertTrue(maxStepChange(new) * 3 < maxStepChange(old), "seed $seed worst tick vs predict")
            assertTrue(stutter(new) * 10 < stutter(tracker), "seed $seed playback ${stutter(new)} vs tracker ${stutter(tracker)}")
            // Once moving it never stalls or runs backwards along the flight.
            for (i in 8 until 100) assertTrue(new[i].subtract(new[i - 1]).length() > 1.0, "seed $seed tick $i")
        }
        println(report)
    }

    @Test fun `the delay grows with arrival jitter and stays bounded`() {
        val path = serverPath(100)
        val even = MunitionFlightPlayback()
        val rough = MunitionFlightPlayback()
        val arrive = arrivals(path.size, 7L, jitter = 6)
        for (clientTick in 0 until 100) {
            even.offer(path[clientTick]); even.advance()
            path.indices.filter { arrive[it] == clientTick }.forEach { rough.offer(path[it]) }
            rough.advance()
        }
        assertEquals(MunitionFlightPlayback.BASE_DELAY_TICKS, even.targetDelayTicks(), 1.0E-9)
        assertTrue(rough.targetDelayTicks() > even.targetDelayTicks())
        assertTrue(rough.targetDelayTicks() <= MunitionFlightPlayback.MAX_DELAY_TICKS)
    }

    @Test fun `stale duplicate and invalid states are rejected`() {
        val path = serverPath(40)
        val playback = MunitionFlightPlayback()
        assertNull(playback.advance())
        assertFalse(playback.active())
        assertTrue(playback.offer(path[30]))
        assertFalse(playback.offer(path[30]), "duplicate")
        assertTrue(playback.offer(path[29]), "late but inside the buffer")
        assertFalse(playback.offer(path[0]), "older than the buffer")
        val nan = path[31].copy(position = Vec3(Double.NaN, 0.0, 0.0))
        assertFalse(playback.offer(nan))
        assertFalse(playback.offer(path[31].copy(yRot = Float.POSITIVE_INFINITY)))
        assertTrue(playback.active())
    }

    @Test fun `starved playback extrapolates briefly then holds`() {
        val path = serverPath(20)
        val playback = MunitionFlightPlayback()
        for (i in 0 until 10) { playback.offer(path[i]); playback.advance() }
        val last = path[9]
        var state = playback.advance()!!
        repeat(10) { state = playback.advance()!! }
        val limit = last.position.add(last.velocity.scale(MunitionFlightPlayback.MAX_EXTRAPOLATION_TICKS))
        assertEquals(0.0, state.position.distanceTo(limit), 1.0E-9)
    }

    @Test fun `a long gap restarts playback at the new state`() {
        val path = serverPath(60)
        val playback = MunitionFlightPlayback()
        for (i in 0 until 10) { playback.offer(path[i]); playback.advance() }
        playback.offer(path[50])
        val state = playback.advance()!!
        assertEquals(0.0, state.position.distanceTo(path[50].position), 1.0E-9)
    }

    @Test fun `heading interpolates across north without spinning`() {
        val playback = MunitionFlightPlayback()
        playback.offer(MunitionFlightPlayback.Sample(1, Vec3.ZERO, Vec3(0.0, 0.0, 1.0), 179f, 0f))
        playback.offer(MunitionFlightPlayback.Sample(2, Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 1.0), -179f, 0f))
        val middle = playback.sample(1.5)!!
        assertEquals(180f, Math.floorMod(middle.yRot.toInt() + 360, 360).toFloat(), 1.01f)
    }
}
