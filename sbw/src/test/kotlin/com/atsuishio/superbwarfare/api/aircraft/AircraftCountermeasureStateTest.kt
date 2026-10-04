package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftCountermeasureStateTest {
    @Test fun `extended chaff continues at three per half second with independent decay and cooldown`() {
        val state = AircraftCountermeasureState()
        state.tick(0, false, true, false, true, 4, 12, 100)
        assertEquals(12, state.tick(60, false, false, false, true, 4, 12, 60).chaffLevel)
        assertEquals(24, state.tick(100, false, false, false, true, 4, 12, 60).chaffLevel)
        assertEquals(12, state.tick(130, false, false, false, true, 4, 12, 60).chaffLevel)
        assertEquals(0, state.tick(160, false, false, false, true, 4, 12, 60).chaffLevel)
        assertFalse(state.tick(499, false, true, false, true, 4, 12, 60).chaffEmitting)
        assertTrue(state.tick(500, false, true, false, true, 4, 12, 60).chaffEmitting)
    }

    @Test fun `long chaff levels do not overwrite threat or emission bits`() {
        val maximum = AircraftCountermeasureState.chaffLevelAt(1200, 1200)
        assertEquals(354, maximum)
        for (threat in 0..2) for (emitting in listOf(false, true)) {
            val packed = AircraftCountermeasureWire.pack(128, maximum, threat, emitting)
            assertEquals(128, AircraftCountermeasureWire.flares(packed))
            assertEquals(maximum, AircraftCountermeasureWire.chaff(packed))
            assertEquals(threat, AircraftCountermeasureWire.threat(packed))
            assertEquals(emitting, AircraftCountermeasureWire.emitting(packed))
        }
        assertThrows(IllegalArgumentException::class.java) { AircraftCountermeasureState.chaffLevelAt(0, 61) }
    }

    @Test fun `missile warning flags ride above the countermeasure bits`() {
        val maximum = AircraftCountermeasureState.chaffLevelAt(1200, 1200)
        for (incoming in 0..15) {
            val packed = AircraftCountermeasureWire.pack(255, maximum, 2, true, incoming)
            assertEquals(incoming, AircraftCountermeasureWire.incoming(packed))
            assertEquals(255, AircraftCountermeasureWire.flares(packed))
            assertEquals(maximum, AircraftCountermeasureWire.chaff(packed))
            assertEquals(2, AircraftCountermeasureWire.threat(packed))
            assertTrue(AircraftCountermeasureWire.emitting(packed))
        }
        assertEquals(0, AircraftCountermeasureWire.incoming(AircraftCountermeasureWire.pack(255, 65535, 3, true)))
    }

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
        val first = state.sample(0, chaff = true)
        assertTrue(first.chaffStarted)
        assertEquals(0, first.chaffLevel)
        assertFalse(state.sample(1, chaff = true).chaffStarted)
        val ramp = listOf(1, 2, 4, 6, 9, 12)
        for (i in 1..6) assertEquals(ramp[i - 1], state.sample(i * 10L).chaffLevel)
        assertEquals(6, state.sample(90).chaffLevel)
        assertEquals(0, state.sample(120).chaffLevel)
        assertFalse(state.sample(459, chaff = true).chaffEmitting)
        val second = state.sample(460, chaff = true)
        assertTrue(second.chaffStarted)
        assertTrue(second.chaffEmitting)
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

    @Test fun `rejecting a flare pair leaves same-tick chaff deployment accepted`() {
        val state = AircraftCountermeasureState()
        val first = state.tick(0, true, true, true, true, 4, 12)
        assertEquals(1, first.flarePairs)
        assertTrue(first.chaffStarted)
        state.abortPair()
        val after = state.tick(0, false, false, true, true, 4, 12)
        assertEquals(0, after.flarePairs)
        assertFalse(after.chaffStarted)
        assertTrue(after.chaffEmitting)
        assertEquals(0, after.flareLevel)
        assertEquals(400, after.chaffCooldown)
    }

    @Test fun `disabled equipment never emits despite held input`() {
        val state = AircraftCountermeasureState()
        val result = state.tick(0, true, true, false, false, 4, 12)
        assertEquals(0, result.flarePairs)
        assertEquals(0, result.chaffLevel)
        assertFalse(result.chaffEmitting)
    }
}
