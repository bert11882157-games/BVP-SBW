package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftMissileGuidanceTest {
    private fun store(mode: String = "INFRARED") = JsonObject().apply {
        add("Guidance", JsonObject().apply {
            addProperty("Mode", mode); addProperty("LockTicks", 30); addProperty("Range", 1024)
            addProperty("ConeDegrees", 12); addProperty("CountermeasureVulnerability", 1)
        })
    }
    @Test fun `three guidance profiles share explicit bounded data`() {
        for (mode in listOf("ACTIVE_RADAR", "SEMI_ACTIVE_RADAR", "INFRARED")) {
            assertEquals(mode, AircraftMissileLauncher.guidance(store(mode))!!.mode)
        }
        assertNull(AircraftMissileLauncher.guidance(JsonObject()))
    }
    @Test fun `fractional dwell unknown modes and nonfinite sensitivity fail admission`() {
        for ((key, value) in listOf("LockTicks" to 1.5, "LockTicks" to -1.0, "LockTicks" to 0.0,
            "Range" to 4097.0, "ConeDegrees" to 0.0, "CountermeasureVulnerability" to -0.1,
            "CountermeasureVulnerability" to 2.01, "CountermeasureVulnerability" to Double.NaN)) {
            val valueStore = store().apply { getAsJsonObject("Guidance").addProperty(key, value) }
            assertThrows(IllegalArgumentException::class.java) { AircraftMissileLauncher.guidance(valueStore) }
        }
        assertThrows(IllegalArgumentException::class.java) { AircraftMissileLauncher.guidance(store("UNKNOWN")) }
        for (value in listOf(0.0, 2.0)) {
            val s = store().apply { getAsJsonObject("Guidance").addProperty("CountermeasureVulnerability", value) }
            assertEquals(value, AircraftMissileLauncher.guidance(s)!!.vulnerability)
        }
    }
}
