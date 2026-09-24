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

    @Test fun fighterViewIsCloserAndLowerThanPreviousPaddedSphere() {
        val points=listOf(Vec3(-8.0,-1.0,0.0),Vec3(8.0,-1.0,0.0),Vec3(0.0,2.0,9.0),Vec3(0.0,-1.0,-9.0))
        val fit=AircraftLoadoutFraming.fit(Vec3.ZERO,points,0f,640,360,70.0,0)
        val oldDistance=points.maxOf { it.length() }*1.3/sin(Math.toRadians(35.0))+3.0
        assertTrue(fit.distance<oldDistance*.8,"Expected noticeably closer fitting")
        assertTrue(fit.position.y<oldDistance*sin(Math.toRadians(22.0)),"Expected lower viewpoint")
    }
}
