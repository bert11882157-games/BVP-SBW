package com.atsuishio.superbwarfare.entity.vehicle.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundRestPolicyTest {
    @Test fun `parked settling tails stop without suppressing real motion or control`() {
        assertTrue(GroundRestPolicy.isAtRest(true, false, 1e-8, false))
        assertFalse(GroundRestPolicy.isAtRest(false, false, 0.0, false))
        assertFalse(GroundRestPolicy.isAtRest(true, true, 0.0, false))
        assertFalse(GroundRestPolicy.isAtRest(true, false, 0.0, true))
        assertFalse(GroundRestPolicy.isAtRest(true, false, 0.01, false))
        assertEquals(4F, GroundRestPolicy.settleAngle(4F, 4.0005F, true))
        assertEquals(4.2F, GroundRestPolicy.settleAngle(4F, 4.2F, true))
        assertEquals(4.0005F, GroundRestPolicy.settleAngle(4F, 4.0005F, false))
        assertEquals(0F, GroundRestPolicy.settleSteering(0.0001F, true))
        assertEquals(0.1F, GroundRestPolicy.settleSteering(0.1F, true))
        assertEquals(0.0001F, GroundRestPolicy.settleSteering(0.0001F, false))
    }

    @Test fun `many parked ticks remain stable and a changed support slope resumes settling`() {
        var pitch = 0.0F
        repeat(200) {
            val noise = if (it % 2 == 0) 0.012F else -0.012F
            pitch = GroundRestPolicy.settleAngle(pitch, pitch + noise, true)
        }
        assertEquals(0F, pitch)
        repeat(100) { pitch = GroundRestPolicy.settleAngle(pitch, pitch + (5F - pitch) * 0.15F, true) }
        assertEquals(5F, pitch, 0.1F)
        assertEquals(0F, GroundRestPolicy.settleSteering(0.01F, true))
        assertEquals(0.01F, GroundRestPolicy.settleSteering(0.01F, false))
    }
}
