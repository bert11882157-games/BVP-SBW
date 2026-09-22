package com.atsuishio.superbwarfare.client.overlay.weapon

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingJoystickArcTest {
    @Test fun speedUsesAcceptedThreeDimensionalMotionAndRejectsStaleControls() {
        val controls = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingControlSurfaceSnapshot(
            100, 0f, 0f, 0f, 0f, 0f, false)
        val snapshot = com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot.EMPTY.copy(
            serverTick = 100, motion = net.minecraft.world.phys.Vec3(3.0, 4.0, 0.0), controlSurfaces = controls)
        assertEquals(360.0, FixedWingHudMetrics.speedKmh(snapshot)!!, 1e-9)
        assertNull(FixedWingHudMetrics.speedKmh(snapshot.copy(serverTick = 101)))
    }

    @Test fun centerIsHiddenAndOnlyNearbyEdgeIsShown() {
        assertEquals(0f, FixedWingJoystickArc.opacity(0.7f, 0f, 1f, 0f))
        assertEquals(1f, FixedWingJoystickArc.opacity(1f, 0f, 1f, 0f), 1e-6f)
        assertEquals(0f, FixedWingJoystickArc.opacity(1f, 0f, -1f, 0f))
        assertEquals(0f, FixedWingJoystickArc.opacity(1f, 0f, 0f, 1f))
        assertTrue(FixedWingJoystickArc.opacity(0.9f, 0f, 1f, 0f) in 0.01f..0.99f)
    }
}
