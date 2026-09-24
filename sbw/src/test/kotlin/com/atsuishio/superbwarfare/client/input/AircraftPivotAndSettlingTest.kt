package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.client.camera.AircraftCameraPivot
import com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AircraftPivotAndSettlingTest {
    @Test fun thinWingAndLongFuselageCannotBalanceOnNarrowFaces() {
        for (half in listOf(Vec3(4.0,.12,2.0), Vec3(.8,1.2,4.0))) {
            var pose = Quaternionf().rotationXYZ(1.5f,.4f,1.4f)
            repeat(100) { pose = WreckDebrisPhysics.settle(pose, Vec3(0.0,1.0,0.0), .12f, half) }
            val broadNormal = if (half.y < half.x) Vector3f(0f,1f,0f) else Vector3f(1f,0f,0f)
            assertTrue(abs(pose.transform(broadNormal).y) > .9999f)
        }
    }
    @Test fun equipmentEditorOrbitKeepsItsHullFramingCenterOnTheOpticalAxis() {
        val pivot=Vec3(8000.0,140.0,-4200.0)
        for(yaw in -180..180 step 15) for(pitch in -85..85 step 17) {
            val eye=AircraftCameraPivot.orbit(pivot,yaw.toFloat(),pitch.toFloat(),17.5)
            assertEquals(17.5,eye.distanceTo(pivot),1e-5)
            val forward=Vec3.directionFromRotation(pitch.toFloat(),yaw.toFloat()).normalize()
            assertTrue(pivot.subtract(eye).normalize().dot(forward)>.999999)
        }
    }
    @Test fun arbitraryDebrisAttitudesSettleOntoAFaceWithoutFlippingOrGainingEnergy() {
        for(pitch in listOf(.3f,1.1f,2.5f)) for(roll in listOf(.2f,1.4f,2.9f)) {
            var pose=Quaternionf().rotationXYZ(pitch,.7f,roll)
            repeat(70) { pose=WreckDebrisPhysics.settle(pose,Vec3(0.0,1.0,0.0),.18f) }
            val alignment=listOf(Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),Vector3f(0f,0f,1f))
                .maxOf { abs(pose.transform(it).y) }
            assertTrue(alignment>.9999f)
        }
        for(speed in listOf(.1,1.0,10.0)) for(slope in listOf(.05,.5,5.0)) {
            val incoming=Vec3(speed,-slope,0.0)
            val response=WreckDebrisPhysics.deflect(incoming,Vec3(0.0,1.0,0.0))
            assertTrue(response.lengthSqr()<=incoming.lengthSqr())
            assertTrue(response.y<=.035)
        }
    }
    @Test fun burnFadesAndCannotOutliveTheTwentySecondWreck() {
        for(seed in 0L..240L) {
            assertEquals(1f,WreckDebrisPhysics.flameStrength(119,seed))
            assertEquals(0f,WreckDebrisPhysics.flameStrength(340,seed))
            var previous=1f
            for(age in 120L..400L) {
                val current=WreckDebrisPhysics.flameStrength(age,seed)
                assertTrue(current<=previous); previous=current
            }
        }
    }
}
