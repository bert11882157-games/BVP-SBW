package com.atsuishio.superbwarfare.api.vehicle.flight

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlightLoadContinuityTest {
    @Test fun periodicAbsoluteRefreshDoesNotSnapAtHelicopterOrJetSpeed() {
        for (speed in listOf(0.0, 1.5, 320.0 / 72.0)) {
            assertFalse(FlightPositionCorrection.shouldSnap(Math.pow(speed * 3.0, 2.0), speed))
            assertTrue(FlightPositionCorrection.shouldSnap(100.0 * 100.0, speed))
        }
        assertTrue(FlightPositionCorrection.shouldSnap(Double.NaN, 1.0))
    }

    @Test fun clientInputGapReleasesKeysButPreservesTheAcceptedTargetAndTurnPolicy() {
        val owner = UUID(0, 1)
        val state = FixedWingPilotIntentState()
        state.bind(owner, 0.0, 0.0, 1.0)
        assertTrue(state.offer(owner, state.controlEpoch, 0, 100,
            0.6, 0.0, 0.8, FixedWingPilotIntent.PITCH_UP, 0.7F))
        val fresh = state.sample(100)!!
        val delayed = state.sample(115)!!
        assertEquals(fresh.copy(manualMask = 0), delayed)
        assertEquals(delayed, state.sample(200))
        assertFalse(state.offer(owner, state.controlEpoch, 0, 201,
            0.0, 0.0, 1.0, 0, -1F))
        assertEquals(delayed, state.sample(201))
    }
}
