package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftCountermeasureStateTest {
    private fun AircraftCountermeasureState.sample(t: Long, flares: Boolean = false,
        chaff: Boolean = false, rate: Int = 4, burst: Int = 12) =
        tick(t, flares, chaff, true, true, rate, burst)

    @Test fun `held flares emit pairs at cadence and each pair expires independently after two seconds`() {
        val state = AircraftCountermeasureState()
        val emitted = mutableListOf<Long>()
        for (t in 0L..49) {
            val out = state.sample(t, flares = true)
            if (out.flarePairs > 0) emitted.add(t)
            if (t == 39L) assertEquals(8, out.flareLevel)
            if (t == 40L) assertEquals(8, out.flareLevel)
        }
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), emitted)
        assertEquals(400, state.sample(50, flares = true).flareCooldown)
        for (t in 51L..449) assertEquals(0, state.sample(t, flares = true).flarePairs)
        assertEquals(0, state.sample(449).flareLevel)
        assertEquals(1, state.sample(450, flares = true).flarePairs)
    }

    @Test fun `tapping cannot reset rate or burst count and odd rates retain fractional timing`() {
        val held = AircraftCountermeasureState()
        val tapped = AircraftCountermeasureState()
        var heldCount = 0; var tappedCount = 0
        for (t in 0L..60) {
            heldCount += held.sample(t, flares = true, rate = 3, burst = 128).flarePairs
            tappedCount += tapped.sample(t, flares = t % 2L == 0L, rate = 3, burst = 128).flarePairs
        }
        assertEquals(5, heldCount)
        assertTrue(tappedCount <= heldCount)
    }

    @Test fun `chaff ramps one two three per half second then decays for three seconds`() {
        val state = AircraftCountermeasureState()
        assertEquals(0, state.sample(0, chaff = true).chaffLevel)
        val ramp = listOf(1, 2, 4, 6, 9, 12)
        for (i in 1..6) assertEquals(ramp[i - 1], state.sample(i * 10L).chaffLevel)
        assertEquals(6, state.sample(90).chaffLevel)
        assertEquals(0, state.sample(120).chaffLevel)
        assertFalse(state.sample(459, chaff = true).chaffEmitting)
        assertTrue(state.sample(460, chaff = true).chaffEmitting)
        assertEquals(1, state.sample(470, chaff = true).chaffLevel)
    }

    @Test fun `cooldown and partial burst expenditure survive unload without retained decoys`() {
        val before = AircraftCountermeasureState()
        before.sample(0, flares = true)
        val after = AircraftCountermeasureState()
        after.restore(20, before.flareReadyAt, before.chaffReadyAt, before.burstExpenditure())
        assertEquals(0, after.sample(20).flareLevel)
        assertEquals(2, after.burstExpenditure())
        for (t in listOf(21L, 31L, 41L, 51L)) after.sample(t, flares = true)
        assertEquals(400, after.sample(61, flares = true).flareCooldown)
        val reload = AircraftCountermeasureState()
        reload.restore(80, after.flareReadyAt, after.chaffReadyAt, after.burstExpenditure())
        assertEquals(381, reload.sample(80, flares = true).flareCooldown)
        assertEquals(0, reload.sample(80, flares = true).flarePairs)
    }

    @Test fun `canceled pair cannot count as launched or consume the final burst`() {
        val state = AircraftCountermeasureState()
        state.sample(0, flares = true, burst = 2)
        state.abortPair()
        val result = state.sample(0)
        assertEquals(0, result.flareLevel)
        assertEquals(0, result.flareCooldown)
        assertEquals(0, state.burstExpenditure())
    }

    @Test fun `disabled equipment never emits despite held input`() {
        val state = AircraftCountermeasureState()
        val result = state.tick(0, true, true, false, false, 4, 12)
        assertEquals(0, result.flarePairs)
        assertEquals(0, result.chaffLevel)
        assertFalse(result.chaffEmitting)
    }

    @Test fun `128 flare magazine resumes its final eight flares after unloading`() {
        val restored = AircraftCountermeasureState()
        restored.restore(200, 0, 0, 120)
        var flares = 0
        for (tick in 200L..207L) {
            flares += 2 * restored.sample(tick, flares = true, rate = 20, burst = 128).flarePairs
        }
        assertEquals(8, flares)
        assertEquals(400, restored.sample(207, rate = 20, burst = 128).flareCooldown)
        for (tick in 208L..606L) {
            assertEquals(0, restored.sample(tick, flares = true, rate = 20, burst = 128).flarePairs)
        }
        assertEquals(1, restored.sample(607, flares = true, rate = 20, burst = 128).flarePairs)
    }

    @Test fun `failed final flare pair preserves magazine contents and does not start reload`() {
        val state = AircraftCountermeasureState()
        state.restore(100, 0, 0, 126)
        assertEquals(1, state.sample(101, flares = true, rate = 20, burst = 128).flarePairs)
        state.abortPair()
        assertEquals(126, state.burstExpenditure())
        assertEquals(0, state.sample(101, rate = 20, burst = 128).flareCooldown)
        val retry = state.sample(103, flares = true, rate = 20, burst = 128)
        assertEquals(1, retry.flarePairs)
        assertEquals(400, retry.flareCooldown)
    }
}
