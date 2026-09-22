package com.atsuishio.superbwarfare.api.vehicle.aim

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleAimCaptureTest {
    @Test fun `capture grows with zoom but remains local and rejects invalid errors`() {
        assertTrue(VehicleAimMath.shouldSnap(0.1F, 0.1F, 1F))
        assertFalse(VehicleAimMath.shouldSnap(0.4F, 0.1F, 1F))
        assertTrue(VehicleAimMath.shouldSnap(0.4F, 0.1F, 8F))
        assertFalse(VehicleAimMath.shouldSnap(1F, 0F, 1000F))
        assertFalse(VehicleAimMath.shouldSnap(Float.NaN, 0F, 8F))
        assertFalse(VehicleAimMath.shouldSnap(0.4F, 0F, Float.NaN))
    }
}
