package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingAirbrakeTuningTest {
    @Test fun matchedSpeedIdleAirbrakeMeaningfullyReducesFormerDeceleration() {
        for (speed in listOf(30.0,60.0,100.0)) for(density in listOf(0.25,1.0)) {
            val h=FixedWingHandlingProfile.GAME_JET
            fun sample(brake:Boolean,legacyEquivalent:Boolean):FixedWingFlightModel {
                val profile=if(legacyEquivalent) h.copy(airbrakeDragPerMetre=h.airbrakeDragPerMetre*0.45/0.25) else h
                val m=FixedWingFlightModel(profile)
                repeat(10){assertTrue(m.step(it.toLong(),0.0,0.0,speed,false,true,
                    airbrakeRequested=brake,airDensityRatio=density))}
                assertEquals(0.0,m.throttle)
                if(brake) assertEquals(1.0,m.airbrake)
                return m
            }
            val coast=sample(false,false)
            val current=sample(true,false)
            val previous=sample(true,true)
            assertTrue(current.dragAccelerationMps2>coast.dragAccelerationMps2)
            // Force substeps integrate their own speed: stronger drag also reduces the
            // speed used by the next force evaluation. Compare the resulting contribution.
            val ratio=(coast.velocityZ-current.velocityZ)/(coast.velocityZ-previous.velocityZ)
            assertTrue(ratio in 0.50..0.65,"speed=$speed density=$density added speed-loss ratio=$ratio")
            assertTrue(current.velocityZ>previous.velocityZ)
            assertTrue(current.stepDragWorkPerKg<0.0)
        }
    }
}
