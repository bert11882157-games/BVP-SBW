package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class VehicleDeathEffectsTest {
    @Test fun `compact observer exclusion is scoped and restores after nested exception`() {
        val far = UUID.randomUUID()
        val near = UUID.randomUUID()
        assertTrue(VehicleDeathEffects.allowsFullFx(far))
        VehicleDeathEffects.withExclusions(setOf(far)) {
            assertFalse(VehicleDeathEffects.allowsFullFx(far))
            assertTrue(VehicleDeathEffects.allowsFullFx(near))
            assertThrows(IllegalStateException::class.java) {
                VehicleDeathEffects.withExclusions(null) {
                    assertTrue(VehicleDeathEffects.allowsFullFx(far), "Unrelated nested projectile effect keeps its audience")
                    throw IllegalStateException("fixture")
                }
            }
            assertFalse(VehicleDeathEffects.allowsFullFx(far))
        }
        assertTrue(VehicleDeathEffects.allowsFullFx(far))
    }
}
