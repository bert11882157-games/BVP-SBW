package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.subdata.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PassengerWeaponSlewStopTest {
    // A belly gun's stop off its own main gear: aft, below 16 degrees down.
    private fun binding() = PassengerWeaponStationBinding().apply {
        weaponId = "BellyGun"
        weaponKind = PassengerWeaponStationWeaponKind.AUTOCANNON
        slewStops = listOf(PassengerWeaponFireExclusion().apply {
            yaw = listOf(150F, 180F); pitch = listOf(-60F, -16F)
        })
    }

    @Test fun `free motion outside a stop is untouched`() {
        val pose = binding().stopSlew(0F, -40F, 10F, -45F)
        assertArrayEquals(floatArrayOf(10F, -45F), pose)
    }

    @Test fun `traverse holds at a stop while elevation carries on`() {
        val pose = binding().stopSlew(149F, -40F, 152F, -41F)
        assertArrayEquals(floatArrayOf(149F, -41F), pose)
    }

    @Test fun `inside a stop the gun lifts just clear of it towards level`() {
        val station = binding()
        val pose = station.stopSlew(160F, -30F, 161F, -31F)
        assertEquals(161F, pose[0])
        assertTrue(pose[1] > -16F && pose[1] < -15.9F)
        assertTrue(station.stopSlew(pose[0], pose[1], pose[0], pose[1]).contentEquals(pose))
    }

    @Test fun `no stops or a bad stop leave the step alone`() {
        val station = binding()
        station.slewStops = emptyList()
        assertArrayEquals(floatArrayOf(170F, -40F), station.stopSlew(160F, -40F, 170F, -40F))
        station.slewStops = listOf(PassengerWeaponFireExclusion().apply { yaw = listOf(10F, 0F); pitch = listOf(-60F, -16F) })
        assertFalse(station.hasTypedIdentity())
        assertArrayEquals(floatArrayOf(170F, -40F), station.stopSlew(160F, -40F, 170F, -40F))
    }
}
