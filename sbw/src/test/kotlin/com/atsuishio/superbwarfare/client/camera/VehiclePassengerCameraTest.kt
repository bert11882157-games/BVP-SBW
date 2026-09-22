package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehiclePassengerCameraTest {
    @Test fun `transport passengers in tank typed IFVs and APCs require third person`() {
        for (type in listOf(VehicleType.TANK, VehicleType.APC)) {
            assertTrue(transportPassengerView(type, 1, false))
            assertTrue(transportPassengerView(type, 8, false))
            assertFalse(transportPassengerView(type, 0, false))
            assertFalse(transportPassengerView(type, 1, true))
            assertFalse(transportPassengerView(type, -1, false))
        }
    }

    @Test fun `aircraft and unrelated seat types retain their views`() {
        assertFalse(transportPassengerView(null, 1, false))
        for (type in VehicleType.entries.filter { it != VehicleType.TANK && it != VehicleType.APC }) {
            assertFalse(transportPassengerView(type, 1, false))
        }
    }
}
