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
    @Test fun fuselageSectionsNeverChooseTheirCutEndAndWingsAlwaysRestFlat() {
        val up = Vec3(0.0,1.0,0.0)
        for (pose in listOf(Quaternionf(), Quaternionf().rotationX(1.5f), Quaternionf().rotationXYZ(.4f,1.1f,1.5f))) {
            // A short section: the cut (z) end is its broadest face, yet it must lie on a side or belly.
            val section = WreckDebrisPhysics.restAxis(pose, up, Vec3(1.3,1.0,.6), WreckDebrisPhysics.Rest.FUSELAGE)
            assertEquals(1, section, "lowest center of mass among the allowed faces")
            assertEquals(0, WreckDebrisPhysics.restAxis(pose, up, Vec3(.8,1.4,.6), WreckDebrisPhysics.Rest.FUSELAGE))
            // Wing chord thinner than the (pylon-inflated) thickness still lands flat.
            assertEquals(1, WreckDebrisPhysics.restAxis(pose, up, Vec3(3.0,.4,.3), WreckDebrisPhysics.Rest.WING))
            val flat = WreckDebrisPhysics.restTarget(pose, up, Vec3(3.0,.4,.3), WreckDebrisPhysics.Rest.WING)
            assertTrue(abs(flat.transform(Vector3f(0f,1f,0f)).y) > .9999f)
        }
        // Near-equal allowed faces keep the one closest to the current attitude.
        val rolled = Quaternionf().rotationZ(1.4f)
        assertEquals(0, WreckDebrisPhysics.restAxis(rolled, up, Vec3(1.0,1.05,3.0), WreckDebrisPhysics.Rest.FUSELAGE))
    }
    @Test fun centerOfMassOutsideTheSupportsToppleOverTheNearestEdge() {
        val hint = Vec3(1.0,0.0,0.0)
        val square = listOf(Vec3(-1.0,-1.0,-1.0),Vec3(1.0,-1.0,-1.0),Vec3(1.0,-1.0,1.0),Vec3(-1.0,-1.0,1.0))
        assertNull(WreckDebrisPhysics.overhang(square, .03, hint), "four-corner footprint holds the center")
        val shifted = square.map { it.add(1.5,0.0,0.0) }
        val tip = WreckDebrisPhysics.overhang(shifted, .03, hint)!!
        assertEquals(.5, tip.pivot.x, 1e-9)
        assertEquals(-1.0, tip.lean.x, 1e-9, "the center hangs over the -x edge")
        val barely = square.map { it.add(.99,0.0,0.0) }
        assertEquals(-1.0, WreckDebrisPhysics.overhang(barely, .03, hint)!!.lean.x, 1e-9,
            "a center barely inside the edge falls outward, never inward")
        // A line of support never balances the piece, even exactly beneath the center.
        val edge = listOf(Vec3(0.0,-1.0,-2.0),Vec3(0.0,-1.0,2.0))
        val balance = WreckDebrisPhysics.overhang(edge, .03, Vec3(.3,0.0,.9))!!
        assertEquals(0.0, balance.lean.z, 1e-9)
        assertEquals(1.0, balance.lean.x, 1e-9)
        val point = WreckDebrisPhysics.overhang(listOf(Vec3(.2,-1.0,0.0)), .03, hint)!!
        assertEquals(-1.0, point.lean.x, 1e-9)
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
