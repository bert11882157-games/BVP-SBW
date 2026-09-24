package com.atsuishio.superbwarfare.client.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class AircraftLoadoutFramingTest {
    @Test fun wideAndLongAircraftFitWithoutHidingPylonsBehindScreenChrome() {
        val center=Vec3(1530.0,75.0,-2370.0)
        for((width,height) in listOf(320 to 240,640 to 360,344 to 144,360 to 640))
        for(fov in listOf(30.0,70.0,110.0))
        for(bayRows in listOf(0,2))
        for(yaw in listOf(0f,47f,179f)) {
            val forward=Vec3.directionFromRotation(AircraftLoadoutFraming.PITCH,yaw).normalize()
            val right=forward.cross(Vec3(0.0,1.0,0.0)).normalize()
            val up=right.cross(forward).normalize()
            // Broad wings and a long hull on different sides of the focal plane expose
            // fits that use horizontal FOV or only two diagonal box corners.
            val points=listOf(-30.0,30.0).flatMap { x -> listOf(-3.0,5.0).flatMap { y ->
                listOf(-28.0,22.0).map { z -> center.add(x,y,z) } } }
            val fit=AircraftLoadoutFraming.fit(center,points,yaw,width,height,fov,bayRows)
            val tangent=tan(Math.toRadians(fov/2))
            val top=min(66.0,height*.3)
            val bottom=max(top+height*.25,height-46.0-bayRows*23.0).coerceAtMost(height-16.0)
            for(point in points) {
                val relative=point.subtract(fit.position)
                val depth=relative.dot(forward)
                assertTrue(depth>0)
                val x=width/2.0+relative.dot(right)/depth/tangent*height/2
                val y=height/2.0-relative.dot(up)/depth/tangent*height/2
                assertTrue(x in 23.9..(width-23.9),"horizontal clipping: $x at $width x $height")
                assertTrue(y in (top-.01)..(bottom+.01),"vertical clipping: $y at $width x $height")
            }
        }
    }

    private fun assertFramed(fit: AircraftLoadoutFraming.Fit, points: List<Vec3>, yaw: Float,
                             width: Int, height: Int, fov: Double, bayRows: Int) {
        val forward=Vec3.directionFromRotation(fit.pitch,yaw).normalize()
        val right=forward.cross(Vec3(0.0,1.0,0.0)).normalize()
        val up=right.cross(forward).normalize()
        val tangent=tan(Math.toRadians(fov/2))
        val top=min(66.0,height*.3)
        val bottom=max(top+height*.25,height-46.0-bayRows*23.0).coerceAtMost(height-16.0)
        for(point in points) {
            val relative=point.subtract(fit.position)
            val depth=relative.dot(forward)
            assertTrue(depth>0)
            val x=width/2.0+relative.dot(right)/depth/tangent*height/2
            val y=height/2.0-relative.dot(up)/depth/tangent*height/2
            assertTrue(x in 23.9..(width-23.9),"horizontal clipping: $x at $width x $height")
            assertTrue(y in (top-.01)..(bottom+.01),"vertical clipping: $y at $width x $height")
        }
    }

    @Test fun groundViewLooksUpAtWingsFromJustAboveTheGround() {
        val groundY=64.0
        // Fighter on its gear: fuselage centred 2 blocks up, wing pylons 1.5 blocks up.
        val center=Vec3(100.0,groundY+2.0,-40.0)
        val points=listOf(Vec3(-8.0,-.5,0.0),Vec3(8.0,-.5,0.0),Vec3(0.0,2.0,9.0),Vec3(0.0,-1.8,-9.0),
            Vec3(-4.0,-.5,1.0),Vec3(4.0,-.5,1.0)).map { center.add(it) }
        for((width,height) in listOf(640 to 360,320 to 240,360 to 640))
        for(fov in listOf(30.0,70.0,110.0))
        for(yaw in listOf(0f,47f,179f)) {
            val fit=AircraftLoadoutFraming.fitFromGround(center,points,yaw,width,height,fov,0,groundY)
            assertTrue(fit.pitch<0f,"Expected an upward view, got pitch ${fit.pitch}")
            assertTrue(fit.pitch>=AircraftLoadoutFraming.MIN_PITCH)
            val eyeHeight=fit.position.y-groundY
            assertTrue(eyeHeight>=AircraftLoadoutFraming.EYE_CLEARANCE_BLOCKS-1e-9,"eye below clearance: $eyeHeight")
            assertTrue(eyeHeight<AircraftLoadoutFraming.EYE_CLEARANCE_BLOCKS+.05,"eye not at ground level: $eyeHeight")
            // The wing pylons are above the eye, so they are seen from below.
            assertTrue(points.take(2).all { it.y>fit.position.y })
            assertFramed(fit,points,yaw,width,height,fov,0)
        }
    }

    @Test fun groundViewKeepsElevatedViewWhenItIsAlreadyLowEnough() {
        val points=listOf(Vec3(-1.0,0.0,0.0),Vec3(1.0,0.0,0.0))
        val fit=AircraftLoadoutFraming.fitFromGround(Vec3.ZERO,points,0f,640,360,70.0,0,10.0)
        assertEquals(AircraftLoadoutFraming.PITCH,fit.pitch)
    }

    @Test fun groundViewUsesSteepestPitchWhenTargetIsUnreachable() {
        val points=listOf(Vec3(-8.0,0.0,0.0),Vec3(8.0,0.0,0.0))
        val fit=AircraftLoadoutFraming.fitFromGround(Vec3.ZERO,points,0f,640,360,70.0,0,-1000.0)
        assertEquals(AircraftLoadoutFraming.MIN_PITCH,fit.pitch)
    }

    @Test fun fighterViewIsCloserAndLowerThanPreviousPaddedSphere() {
        val points=listOf(Vec3(-8.0,-1.0,0.0),Vec3(8.0,-1.0,0.0),Vec3(0.0,2.0,9.0),Vec3(0.0,-1.0,-9.0))
        val fit=AircraftLoadoutFraming.fit(Vec3.ZERO,points,0f,640,360,70.0,0)
        val oldDistance=points.maxOf { it.length() }*1.3/sin(Math.toRadians(35.0))+3.0
        assertTrue(fit.distance<oldDistance*.8,"Expected noticeably closer fitting")
        assertTrue(fit.position.y<oldDistance*sin(Math.toRadians(22.0)),"Expected lower viewpoint")
    }
}
