package com.atsuishio.superbwarfare.api.effect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MissileWirePolicyTest {
    @Test fun nativeWireAdmissionRequiresBothLauncherAndRound() {
        for (type in listOf("tow", "sodayo_pick_up_tow", "bradley", "lav_25"))
            assertTrue(MissilePresentation.nativePhysicalWire("superbwarfare:$type", "Missile"))
        assertTrue(MissilePresentation.nativePhysicalWire("superbwarfare:bmp_2", "Missile"))
        assertFalse(MissilePresentation.nativePhysicalWire("superbwarfare:mi_28", "DriverMissile"))
        assertFalse(MissilePresentation.nativePhysicalWire("superbwarfare:tow", "Cannon"))
        assertFalse(MissilePresentation.nativePhysicalWire("superbwarfare:mi_28", "Missile"))
    }
    @Test fun nativeWireCountDistinguishesTowKonkursAndRadioGuidance() {
        for (type in listOf("tow", "sodayo_pick_up_tow", "bradley", "lav_25")) {
            assertEquals(2, MissilePresentation.nativePhysicalWireCount("superbwarfare:$type", "Missile"))
            assertEquals(0, MissilePresentation.nativePhysicalWireCount("superbwarfare:$type", "Cannon"))
        }
        assertEquals(1, MissilePresentation.nativePhysicalWireCount("superbwarfare:bmp_2", "Missile"))
        assertEquals(0, MissilePresentation.nativePhysicalWireCount("superbwarfare:mi_28", "Missile"))
    }
}
