package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftClusterBombTest {
    @Test fun spreadIsBoundedDistinctAndRepeatable() {
        val points = AircraftClusterBomb.spread(12, .35, .2)
        assertEquals(12, points.size)
        assertEquals(12, points.toSet().size)
        assertEquals(points, AircraftClusterBomb.spread(12, .35, .2))
        assertTrue(points.all { it.horizontalDistance() <= .35 && it.y < 0 })
        assertTrue(points.any { it.x > 0 } && points.any { it.x < 0 })
        assertTrue(points.any { it.z > 0 } && points.any { it.z < 0 })
    }
    @Test fun configurationRejectsUnboundedCountsLifetimesAndInvalidNumbers() {
        fun config(count: Int = 12, life: Int = 100, spread: Double = .35) =
            AircraftClusterBomb.Configuration(count, 12.0, spread, 80f, 1.5f, life)
        assertEquals(12, config().count)
        // real loads (owner 2026-09-30): the Mk 20 Rockeye carries 247
        assertEquals(247, config(count = 247).count)
        assertThrows(IllegalArgumentException::class.java) { config(count = AircraftClusterBomb.MAX_SUBMUNITIONS + 1) }
        assertThrows(IllegalArgumentException::class.java) { config(count = 0) }
        assertThrows(IllegalArgumentException::class.java) { config(life = 201) }
        assertThrows(IllegalArgumentException::class.java) { config(spread = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { AircraftClusterBomb.spread(10000, .35, 0.0) }
    }
    @Test fun typedArmorChildrenCannotAlsoInheritLegacyRadiusDamage() {
        val heat = ResourceLocation("berts_vehicle_pack", "aircraft_bombs/cbu99_heat")
        val efp = ResourceLocation("berts_vehicle_pack", "aircraft_bombs/cbu97_efp")
        assertEquals("HEAT", AircraftClusterBomb.Configuration(24, 12.0, .45, 0f, 1f, 100,
            "HEAT", bombletProfile = heat).mode)
        assertEquals(4, AircraftClusterBomb.Configuration(10, 16.0, .35, 0f, 1f, 120,
            "SENSOR_FUZED", sensorRadius = 8.0, sensorShots = 4,
            sensorProjectileProfile = efp).sensorShots)
        assertThrows(IllegalArgumentException::class.java) {
            AircraftClusterBomb.Configuration(24, 12.0, .45, 80f, 1f, 100,
                "HEAT", bombletProfile = heat)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftClusterBomb.Configuration(10, 16.0, .35, 0f, 1f, 120,
                "SENSOR_FUZED", sensorRadius = 17.0, sensorShots = 4,
                sensorProjectileProfile = efp)
        }
    }
    @Test fun dispenserOpensFifteenMetresUp() {
        // still more than a tick above the opening height: keep falling
        assertNull(AircraftClusterBomb.openFraction(20.0, 15.0, 2.0))
        // this tick crosses 15 m: open where the fall reaches it
        assertEquals(0.5, AircraftClusterBomb.openFraction(16.0, 15.0, 2.0)!!, 1e-9)
        assertEquals(1.0, AircraftClusterBomb.openFraction(17.0, 15.0, 2.0)!!, 1e-9)
        // released or found below it: open at once
        assertEquals(0.0, AircraftClusterBomb.openFraction(9.0, 15.0, 2.0)!!, 1e-9)
        // not falling
        assertNull(AircraftClusterBomb.openFraction(16.0, 15.0, 0.0))
    }
}
