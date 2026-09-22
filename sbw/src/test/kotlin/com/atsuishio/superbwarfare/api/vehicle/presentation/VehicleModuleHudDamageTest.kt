package com.atsuishio.superbwarfare.api.vehicle.presentation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleModuleHudDamageTest {
    @Test fun boundariesRepresentDamageTakenAndDestructionOverridesHealth() {
        fun state(health:Float)=VehicleModuleHudDamage.from(VehicleModuleHudHealth(health,100F,false))
        assertEquals(VehicleModuleHudDamage.YELLOW,state(100F))
        assertEquals(VehicleModuleHudDamage.YELLOW,state(67.01F))
        assertEquals(VehicleModuleHudDamage.ORANGE,state(67F))
        assertEquals(VehicleModuleHudDamage.ORANGE,state(34.01F))
        assertEquals(VehicleModuleHudDamage.RED,state(34F))
        assertEquals(VehicleModuleHudDamage.RED,state(0.01F))
        assertEquals(VehicleModuleHudDamage.DESTROYED,state(0F))
        assertEquals(VehicleModuleHudDamage.DESTROYED,
            VehicleModuleHudDamage.from(VehicleModuleHudHealth(100F,100F,true)))
    }
    @Test fun missingOrInvalidModulesStayUnknownAndTracksRemainIndependent() {
        assertEquals(VehicleModuleHudDamage.UNKNOWN,VehicleModuleHudDamage.from(null))
        assertEquals(VehicleModuleHudDamage.UNKNOWN,
            VehicleModuleHudDamage.from(VehicleModuleHudHealth(Float.NaN,40F,false)))
        assertEquals(VehicleModuleHudDamage.UNKNOWN,
            VehicleModuleHudDamage.from(VehicleModuleHudHealth(1F,0F,false)))
        val modules=VehicleModuleHudState(null,VehicleModuleHudHealth(0F,40F,true),
            VehicleModuleHudHealth(40F,40F,false),null)
        assertEquals(VehicleModuleHudDamage.DESTROYED,VehicleModuleHudDamage.from(modules.leftTrack))
        assertEquals(VehicleModuleHudDamage.YELLOW,VehicleModuleHudDamage.from(modules.rightTrack))
    }
    @Test fun serverFloatHealthKeepsExactBoundariesForActualModuleCapacities() {
        for(maximum in listOf(40F,60F,95F)) {
            assertEquals(VehicleModuleHudDamage.ORANGE,VehicleModuleHudDamage.from(
                VehicleModuleHudHealth((maximum*0.67).toFloat(),maximum,false)))
            assertEquals(VehicleModuleHudDamage.RED,VehicleModuleHudDamage.from(
                VehicleModuleHudHealth((maximum*0.34).toFloat(),maximum,false)))
        }
    }
}
