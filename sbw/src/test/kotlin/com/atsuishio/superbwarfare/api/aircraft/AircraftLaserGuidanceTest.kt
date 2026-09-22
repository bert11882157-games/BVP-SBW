package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.GuidedMissileGuidance
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftLaserGuidanceTest {
    @Test fun `point guidance needs no pilot or carrier and repaint reverses lateral demand`() {
        val position = Vec3(0.0, 80.0, 0.0)
        val left = GuidedMissileGuidance.pointDirection(position, Vec3(-100.0, 0.0, 100.0))!!
        val right = GuidedMissileGuidance.pointDirection(position, Vec3(100.0, 0.0, 100.0))!!
        val inherited = Vec3(1.0, 0.2, 0.0)
        val velocity = Vec3(0.0, 0.0, 5.0).add(inherited)
        val a = GuidedMissileGuidance.steer(velocity, inherited, left, 90.0).subtract(inherited)
        val b = GuidedMissileGuidance.steer(velocity, inherited, right, 90.0).subtract(inherited)
        assertTrue(a.x < 0); assertTrue(b.x > 0)
        assertEquals(5.0, a.length(), 1e-10); assertEquals(5.0, b.length(), 1e-10)
    }
    @Test fun `clear and invalid point produce no replacement steering vector`() {
        assertNull(GuidedMissileGuidance.pointDirection(Vec3.ZERO, null))
        assertNull(GuidedMissileGuidance.pointDirection(Vec3.ZERO, Vec3.ZERO))
        assertNull(GuidedMissileGuidance.pointDirection(Vec3.ZERO, Vec3(Double.NaN, 0.0, 0.0)))
    }
}
