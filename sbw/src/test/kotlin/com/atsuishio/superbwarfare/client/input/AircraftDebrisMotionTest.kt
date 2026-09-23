package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisMotion
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftDebrisMotionTest {
    @Test fun fuselageFragmentsBounceTwiceShallowlyAndSeparateWithoutSinking() {
        val part = AircraftDebrisMotion(Vec3(0.0, 2.0, 0.0), Vec3(.8,-.5,0.0), Quaternionf(), Vec3.ZERO,0.0,9.80665/400.0,2)
        repeat(199) {
            part.tick { from,to -> if(to.y<=0&&from.y>=0) from.lerp(to,from.y/(from.y-to.y)) else null }
            assertTrue(part.position.y >= 0)
            assertTrue(part.velocity.y <= .260001)
        }
        assertEquals(2,part.bounces)
        assertTrue(part.grounded)
        assertFalse(part.expired)
        part.tick { _,_->null }
        assertTrue(part.expired)
        val a=Vec3(0.0,1.0,0.0);val b=Vec3(.5,1.0,.3);val half=Vec3(1.0,.5,2.0)
        val rotation=Quaternionf().rotateY(.4f)
        val shift=com.atsuishio.superbwarfare.client.renderer.AircraftDebrisContact.separation(a,rotation,half,b,Quaternionf(),half)!!
        assertEquals(0.0,shift.y,1e-8)
        assertNull(com.atsuishio.superbwarfare.client.renderer.AircraftDebrisContact.separation(a.add(shift),rotation,half,b,Quaternionf(),half))
    }
    @Test fun terrainImpactStartsExactlyTwoSecondsOfDebrisRetention() {
        val wing = AircraftDebrisMotion(Vec3(0.0, 1.0, 0.0), Vec3.ZERO, Quaternionf(), Vec3.ZERO, 0.0)
        repeat(50) { wing.tick { _, _ -> null } }
        assertFalse(wing.expired, "airborne age does not consume ground lifetime")
        wing.tick { _, to -> to }
        assertFalse(wing.expired)
        repeat(39) { wing.tick { _, _ -> error("grounded debris must not query terrain") } }
        assertFalse(wing.expired)
        wing.tick { _, _ -> error("grounded debris must not query terrain") }
        assertTrue(wing.expired)
    }
    @Test fun detachedWingInheritsMomentumFallsAndStopsWithoutExploding() {
        val velocity = Vec3(2.0, .1, .5)
        val wing = AircraftDebrisMotion(Vec3(0.0, 20.0, 0.0), velocity, Quaternionf(), Vec3(.03, .01, -.04), 2.0)
        assertEquals(velocity, wing.velocity, "no ejection impulse")
        repeat(400) {
            wing.tick { from, to -> if (to.y <= 0.0 && from.y > 0.0)
                from.lerp(to, from.y / (from.y - to.y)) else null }
        }
        assertTrue(wing.grounded)
        assertEquals(0.0, wing.position.y, 1e-8)
        assertEquals(Vec3.ZERO, wing.velocity)
        assertTrue(wing.position.x > 60, "forward inertia survives tumbling")
        assertNotEquals(Quaternionf(), wing.orientation)
        val resting = wing.position
        val orientation = Quaternionf(wing.orientation)
        wing.tick { _, _ -> error("grounded debris must not query terrain") }
        assertEquals(resting, wing.position)
        assertEquals(orientation, wing.orientation)
    }
}
