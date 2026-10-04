package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Owner 2026-09-30: every store (bombs, missiles, all) can be released again after 0.1 s. */
class AircraftStoreReleaseRateTest {
    @Test fun `stores release every tenth of a second`() {
        assertEquals(2, AircraftStoreWeapons.RELEASE_INTERVAL_TICKS)
        assertEquals(0.1, AircraftStoreWeapons.RELEASE_INTERVAL_TICKS / 20.0, 1e-9)
        assertEquals(600, AircraftStoreWeapons.RELEASE_RPM)
    }
}
