package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftMissileLaunchersTest {
    private fun def(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun `a definition without weapon seats keeps its single operator`() {
        assertEquals(listOf(0), AircraftMissileLaunchers.weaponSeats(null))
        assertEquals(listOf(0), AircraftMissileLaunchers.weaponSeats(def("""{"Schema":1}""")))
        assertEquals(listOf(1), AircraftMissileLaunchers.weaponSeats(def("""{"WeaponSeat":1}""")))
        assertTrue(AircraftMissileLaunchers.isWeaponSeat(def("""{"WeaponSeat":1}"""), 1))
        assertFalse(AircraftMissileLaunchers.isWeaponSeat(def("""{"WeaponSeat":1}"""), 0))
    }

    @Test fun `both crew members of a two-seat launcher operate the racks, primary first`() {
        val apache = def("""{"WeaponSeat":1,"WeaponSeats":[1,0]}""")
        assertEquals(listOf(1, 0), AircraftMissileLaunchers.weaponSeats(apache))
        assertTrue(AircraftMissileLaunchers.isWeaponSeat(apache, 0))
        assertTrue(AircraftMissileLaunchers.isWeaponSeat(apache, 1))
        assertFalse(AircraftMissileLaunchers.isWeaponSeat(apache, 2))
        assertFalse(AircraftMissileLaunchers.isWeaponSeat(apache, -1))
        // a release reads the slot of the seat that pressed it, a passenger falls back to the primary operator
        assertEquals(0, AircraftMissileLaunchers.releasingSeat(apache, 0))
        assertEquals(1, AircraftMissileLaunchers.releasingSeat(apache, 1))
        assertEquals(1, AircraftMissileLaunchers.releasingSeat(apache, 4))
    }

    @Test fun `malformed weapon seats are ignored and the primary is never lost`() {
        val odd = def("""{"WeaponSeat":1,"WeaponSeats":[0,"x",99,-3,0,true]}""")
        assertEquals(listOf(1, 0), AircraftMissileLaunchers.weaponSeats(odd))
        assertEquals(listOf(1), AircraftMissileLaunchers.weaponSeats(def("""{"WeaponSeat":1,"WeaponSeats":2}""")))
    }
}
