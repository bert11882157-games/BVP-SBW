package com.atsuishio.superbwarfare.api.vehicle.presentation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VehicleScreenEffectsTest {
    @Test fun ridersGetNoShakeExceptTheirOwnGuns() {
        assertTrue(VehicleScreenEffects.blocks(ridingVehicle = true, sentByOwnVehicle = false))
        assertFalse(VehicleScreenEffects.blocks(ridingVehicle = true, sentByOwnVehicle = true))
    }

    @Test fun peopleOutsideVehiclesKeepEveryShake() {
        assertFalse(VehicleScreenEffects.blocks(ridingVehicle = false, sentByOwnVehicle = false))
        assertFalse(VehicleScreenEffects.blocks(ridingVehicle = false, sentByOwnVehicle = true))
    }

    @Test fun nullRiderIsNotShielded() {
        assertFalse(VehicleScreenEffects.shields(null))
    }
}
