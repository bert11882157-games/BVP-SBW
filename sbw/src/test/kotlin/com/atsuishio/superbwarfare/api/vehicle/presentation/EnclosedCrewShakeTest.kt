package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnclosedCrewShakeTest {
    @Test fun closedGroundSeatsAreShielded() {
        for (type in VehicleType.entries) {
            if (type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER) continue
            assertTrue(EnclosedCrewShake.shields(type, true), type.name)
        }
        assertTrue(EnclosedCrewShake.shields(null, true))
    }

    @Test fun exposedSeatsAndAircraftKeepTheirShake() {
        for (type in VehicleType.entries) assertFalse(EnclosedCrewShake.shields(type, false), type.name)
        assertFalse(EnclosedCrewShake.shields(null, false))
        assertFalse(EnclosedCrewShake.shields(VehicleType.AIRPLANE, true))
        assertFalse(EnclosedCrewShake.shields(VehicleType.HELICOPTER, true))
    }
}
