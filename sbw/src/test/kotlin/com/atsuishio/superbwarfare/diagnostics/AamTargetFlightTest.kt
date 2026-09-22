package com.atsuishio.superbwarfare.diagnostics

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AamTargetFlightTest {
    @Test fun `target starts ahead with terrain and aircraft clearance`() {
        val origin = Vec3(10.0, 100.0, 20.0)
        val south = AamTargetFlight.spawnPosition(origin, 0F, 160, 64, 320)
        assertEquals(10.0, south.x, 1e-4)
        assertEquals(180.0, south.z, 1e-4)
        assertEquals(124.0, south.y)
        val west = AamTargetFlight.spawnPosition(origin, 90F, 160, 150, 320)
        assertEquals(-150.0, west.x, 1e-4)
        assertEquals(20.0, west.z, 1e-4)
        assertEquals(182.0, west.y)
    }

    @Test fun `unsafe heights and unbounded distances are rejected`() {
        for (distance in listOf(0, 63, 513, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                AamTargetFlight.spawnPosition(Vec3.ZERO, 0F, distance, 64, 320)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AamTargetFlight.spawnPosition(Vec3(0.0, 300.0, 0.0), 0F, 160, 64, 320)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AamTargetFlight.spawnPosition(Vec3.ZERO, Float.NaN, 160, 64, 320)
        }
    }

    @Test fun `straight real flight covers 108 kilometres per hour without gravity drift`() {
        for (yaw in listOf(-180F, -90F, 0F, 90F, 180F)) {
            val tick = AamTargetFlight.result(yaw)
            assertEquals(108.0, tick.motion.length() * 72.0, 1e-4)
            assertEquals(0.0, tick.motion.y)
            assertEquals(yaw, tick.bodyYaw)
            assertEquals(0F, tick.bodyPitch)
            assertEquals(0F, tick.bodyRoll)
            assertTrue(tick.motionIncludesGravity)
        }
    }
}
