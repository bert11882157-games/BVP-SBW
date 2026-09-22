package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.api.projectile.GuidedMissileGuidance
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuidedTopAttackTest {
    @Test fun loftTransitionsToTargetWithoutChangingMissileSpeed() {
        val target = Vec3(0.0, 64.0, 200.0)
        val far = GuidedMissileGuidance.topAttackDirection(Vec3(0.0, 64.0, 0.0), target)!!
        assertEquals(40.0, far.y)
        val near = GuidedMissileGuidance.topAttackDirection(Vec3(0.0, 80.0, 185.0), target)!!
        assertTrue(near.y < 0)
        val velocity = Vec3(0.0, 0.0, 6.0)
        assertEquals(6.0, GuidedMissileGuidance.steer(velocity, Vec3.ZERO, far, 90.0).length(), 1E-10)
        assertNull(GuidedMissileGuidance.topAttackDirection(Vec3(Double.NaN, 0.0, 0.0), target))
    }
}
