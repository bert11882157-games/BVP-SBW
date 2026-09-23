package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisMotion
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftDebrisMotionTest {
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
