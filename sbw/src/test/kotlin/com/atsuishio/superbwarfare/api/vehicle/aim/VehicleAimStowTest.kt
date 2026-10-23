package com.atsuishio.superbwarfare.api.vehicle.aim

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleAimStowTest {
    @Test fun `a stowed gun rests at boresight inside its limits`() {
        // native xRot/yRot frame: AH-64D M230 +11/-60 el, ±110 az -> pitch range [-11, 60], yaw [-110, 110]
        assertEquals(0F, VehicleAimController.stowedAngle(-11F, 60F))
        assertEquals(0F, VehicleAimController.stowedAngle(-110F, 110F))
        // Ka-50: 9° right / 2.5° left still includes boresight
        assertEquals(0F, VehicleAimController.stowedAngle(-9F, 2.5F))
    }

    @Test fun `a range that excludes boresight stows at its nearest limit`() {
        assertEquals(5F, VehicleAimController.stowedAngle(5F, 30F))
        assertEquals(-4F, VehicleAimController.stowedAngle(-20F, -4F))
        assertEquals(0F, VehicleAimController.stowedAngle(10F, -10F), "inverted data falls back to boresight")
    }
}
