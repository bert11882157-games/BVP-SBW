package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.GuidedMissileGuidance
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftLaserGuidanceTest {
    @Test fun `painted point course cancels lateral launch momentum without changing motor speed`() {
        val inherited = Vec3(2.5, 0.3, 0.0)
        val position = Vec3(0.0, 150.0, 0.0)
        for (target in listOf(Vec3(0.0, 0.0, 800.0), Vec3(-100.0, 0.0, 100.0))) {
            val demand = GuidedMissileGuidance.laserInterceptDirection(position, target, 6.0, inherited)!!
            val relative = demand.normalize().scale(6.0)
            val total = relative.add(inherited)
            assertEquals(6.0, relative.length(), 1e-10)
            assertEquals(1.0, total.normalize().dot(target.subtract(position).normalize()), 1e-10)
        }
    }

    @Test fun `bounded missile trajectory reaches painted terrain after a fast sideways release`() {
        val inherited = Vec3(2.5, 0.0, 0.0)
        val target = Vec3(0.0, 0.0, 800.0)
        var position = Vec3(0.0, 150.0, 0.0)
        var velocity = Vec3(0.0, 0.0, 6.0).add(inherited)
        var nearest = Double.POSITIVE_INFINITY
        repeat(220) {
            val demand = GuidedMissileGuidance.laserInterceptDirection(position, target, 6.0, inherited)
            if (demand != null) velocity = GuidedMissileGuidance.steer(velocity, inherited, demand, 90.0)
            val segment = velocity
            val fraction = target.subtract(position).dot(segment).div(segment.lengthSqr()).coerceIn(0.0, 1.0)
            nearest = minOf(nearest, position.add(segment.scale(fraction)).distanceTo(target))
            position = position.add(segment)
        }
        assertTrue(nearest < 0.5, "Swept trajectory missed the designation by $nearest blocks")
    }
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
