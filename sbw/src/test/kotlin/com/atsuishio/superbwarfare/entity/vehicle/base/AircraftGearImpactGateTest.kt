package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftGearImpactGateTest {
    private fun airborne(gate: AircraftGearImpactGate, first: Long) {
        assertEquals(0.0, gate.sample(first, true, false, 0.0))
        assertEquals(0.0, gate.sample(first + 1, true, false, 0.0))
    }

    @Test fun softAndHardTouchdownsEmitOnceButStationarySupportStaysQuiet() {
        for (speed in listOf(0.029858995103026142, 0.05, 0.5)) {
            val gate = AircraftGearImpactGate()
            airborne(gate, 0)
            assertEquals(speed, gate.sample(2, true, true, speed))
            for (tick in 3L..2000L) assertEquals(0.0,
                gate.sample(tick, true, true, if (tick % 2L == 0L) 0.0245 else speed))
        }
    }

    @Test fun parkedSpawnAndOneTickContactFlickerNeverCreateShake() {
        val gate = AircraftGearImpactGate()
        assertEquals(0.0, gate.sample(0, true, true, 0.0245))
        for (tick in 1L..200L) assertEquals(0.0,
            gate.sample(tick, true, tick % 2L == 0L, 0.5))
    }

    @Test fun cooldownSuppressesRapidBounceAndLaterLandingCanRearm() {
        val gate = AircraftGearImpactGate()
        airborne(gate, 0)
        assertEquals(0.1, gate.sample(2, true, true, 0.1))
        airborne(gate, 3)
        assertEquals(0.0, gate.sample(5, true, true, 0.5))
        for (tick in 6L..10L) assertEquals(0.0, gate.sample(tick, true, false, 0.0))
        assertEquals(0.5, gate.sample(11, true, true, 0.5))
        assertEquals(0.0, gate.sample(11, true, true, 0.5))
    }

    @Test fun unknownQueriesTimingGapsAndInvalidSpeedsCannotFabricateOnset() {
        for (kind in listOf("unknown", "gap", "invalid")) {
            val gate = AircraftGearImpactGate()
            airborne(gate, 0)
            if (kind == "unknown") assertEquals(0.0, gate.sample(2, false, false, 0.0))
            if (kind == "invalid") assertEquals(0.0, gate.sample(2, true, false, Double.NaN))
            assertEquals(0.0, gate.sample(if (kind == "gap") 4 else 3, true, true, 0.5))
        }
    }

    @Test fun gentleSettlingBelowThresholdConsumesTheEpisodeQuietly() {
        val gate = AircraftGearImpactGate()
        airborne(gate, 0)
        assertEquals(0.0, gate.sample(2, true, true, 0.005))
        assertEquals(0.0, gate.sample(3, true, true, 0.5))
    }
}
