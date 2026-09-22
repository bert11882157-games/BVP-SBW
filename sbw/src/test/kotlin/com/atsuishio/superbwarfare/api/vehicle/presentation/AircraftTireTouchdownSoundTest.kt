package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.entity.vehicle.base.AircraftGearImpactGate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftTireTouchdownSoundTest {
    @Test fun quietSlowContactsAndBoundedLoudnessAtFlightSpeed() {
        for(speed in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,0.0,0.24))
            assertEquals(0F,VehicleLandingImpactPresentation.tireVolume(speed))
        assertTrue(VehicleLandingImpactPresentation.tireVolume(0.25)>0F)
        assertTrue(VehicleLandingImpactPresentation.tireVolume(2.0)>
            VehicleLandingImpactPresentation.tireVolume(0.5))
        assertEquals(0.95F,VehicleLandingImpactPresentation.tireVolume(100.0))
    }
    @Test fun separateGearContactsRearmButContinuousRollingCannotRetrigger() {
        val main=AircraftGearImpactGate();val nose=AircraftGearImpactGate()
        var mainSounds=0;var noseSounds=0
        for(tick in 0L..80L) {
            val airborne=tick<2 || tick in 50..54
            val mainContact=!airborne
            val noseContact=!airborne && tick>=6
            if(main.sample(tick,true,mainContact,0.1)>0) mainSounds++
            if(nose.sample(tick,true,noseContact,0.1)>0) noseSounds++
        }
        assertEquals(2,mainSounds);assertEquals(2,noseSounds)
    }
}
