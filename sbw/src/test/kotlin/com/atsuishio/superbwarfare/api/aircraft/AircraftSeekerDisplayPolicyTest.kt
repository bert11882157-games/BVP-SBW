package com.atsuishio.superbwarfare.api.aircraft
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftSeekerDisplayPolicyTest {
    private fun channel(id: String, category: String, mode: String, slot: String) =
        AircraftSeekerDisplayPolicy.Channel(id, category, mode, slot)
    @Test fun `secondary infrared AAM wins presentation while source channels remain independent`() {
        val primary = channel("AGM", "LASER_GUIDED", "SEMI_ACTIVE_RADAR", "PRIMARY")
        val secondary = channel("R60", "AIR_TO_AIR", "INFRARED", "SECONDARY")
        val channels = listOf(primary, secondary)
        assertSame(secondary, AircraftSeekerDisplayPolicy.choose(channels))
        assertEquals(listOf(primary, secondary), channels)
        val radar = channel("Radar", "AIR_TO_AIR", "ACTIVE_RADAR", "PRIMARY")
        assertSame(secondary, AircraftSeekerDisplayPolicy.choose(listOf(radar, secondary)))
    }
    @Test fun `same class preserves slot order and other AAM precedes guided ground weapon`() {
        val primary = channel("R60A", "AIR_TO_AIR", "INFRARED", "PRIMARY")
        val secondary = channel("R60B", "AIR_TO_AIR", "INFRARED", "SECONDARY")
        assertSame(primary, AircraftSeekerDisplayPolicy.choose(listOf(primary, secondary)))
        val ground = channel("AGM", "LASER_GUIDED", "SEMI_ACTIVE_RADAR", "PRIMARY")
        val radar = channel("Radar", "AIR_TO_AIR", "ACTIVE_RADAR", "SECONDARY")
        assertSame(radar, AircraftSeekerDisplayPolicy.choose(listOf(ground, radar)))
        assertSame(ground, AircraftSeekerDisplayPolicy.choose(listOf(ground)))
        assertNull(AircraftSeekerDisplayPolicy.choose(emptyList()))
    }
}
