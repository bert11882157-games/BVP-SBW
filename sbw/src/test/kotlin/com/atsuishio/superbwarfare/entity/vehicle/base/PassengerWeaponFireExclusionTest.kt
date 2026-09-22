package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.subdata.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PassengerWeaponFireExclusionTest {
    private fun binding() = PassengerWeaponStationBinding().apply {
        weaponId = "RearGun"
        weaponKind = PassengerWeaponStationWeaponKind.AUTOCANNON
        fireExclusions = listOf(PassengerWeaponFireExclusion().apply {
            yaw = listOf(-2F, 2F); pitch = listOf(-11F, 25F)
        })
    }
    @Test fun `tail mask blocks centerline through upper boundary but preserves the surrounding arc`() {
        val station = binding()
        for (yaw in listOf(-2F, 0F, 2F)) for (pitch in listOf(-11F, 0F, 25F))
            assertFalse(station.permitsFire(yaw, pitch))
        assertTrue(station.permitsFire(0F, 25.01F))
        assertTrue(station.permitsFire(-2.01F, 0F))
        assertTrue(station.permitsFire(2.01F, 0F))
        assertFalse(station.permitsFire(Float.NaN, 0F))
    }
    @Test fun `bad authored exclusion fails closed and legacy empty exclusion preserves firing`() {
        val station = binding()
        station.fireExclusions[0].yaw = listOf(-181F, 2F)
        assertFalse(station.hasTypedIdentity())
        assertFalse(station.permitsFire(90F, 30F))
        station.fireExclusions = emptyList()
        assertTrue(station.permitsFire(0F, 0F))
    }
}
