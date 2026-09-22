package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlightInstrumentRebaseTest {
    @Test fun frequentSnapshotsCannotLatchAfterburnerWhileMotionRemainsSmooth() {
        var displayed = VehicleFlightInstrumentSnapshot.EMPTY.copy(
            controlSurfaces = FixedWingControlSurfaceSnapshot(0, 0F, 0F, 0F, 0F, 0F, false),
        )
        for (tick in 1..40) {
            val enabled = tick in 4..20
            val target = displayed.copy(
                sequence = tick, serverTick = tick.toLong(), throttle = 1.0,
                controlSurfaces = FixedWingControlSurfaceSnapshot(
                    tick.toLong(), 1F, 0F, 0F, 0F, 1F, enabled,
                ),
            )
            val before = displayed.throttle
            displayed = VehicleFlightInstrumentSnapshot.interpolate(displayed, target, 1F / 3F)
            assertEquals(enabled, displayed.controlSurfaces!!.afterburnerActive)
            assertEquals(before + (1.0 - before) / 3.0, displayed.throttle, 1e-7)
            assertTrue(displayed.throttle < 1.0)
        }
    }
}
