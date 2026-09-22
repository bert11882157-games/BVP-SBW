package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftCountermeasureDefinition
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftCountermeasureDefinitionTest {
    @Test fun `creation data independently enables equipment with hull local dispenser positions`() {
        val definition = Json.decodeFromString<AircraftCountermeasureDefinition>(
            """{"Flares":true,"Chaff":true,"FlaresPerSecond":6,"FlaresPerBurst":18,
              "FlareLeftPos":[-2,-0.1,1],"FlareRightPos":[2,-0.1,1]}""")
        assertTrue(definition.flares)
        assertTrue(definition.chaff)
        assertEquals(18, definition.flaresPerBurst)
        assertEquals(-2.0, definition.flareLeftPos.x)
        assertEquals(2.0, definition.flareRightPos.x)
        assertTrue(definition.radarWarningReceiver)
        assertFalse(AircraftCountermeasureDefinition().flares)
        assertFalse(AircraftCountermeasureDefinition().chaff)
    }

    @Test fun `invalid cadence and unpaired burst sizes are rejected before use`() {
        assertThrows(IllegalArgumentException::class.java) { AircraftCountermeasureDefinition(flaresPerSecond = 0) }
        assertThrows(IllegalArgumentException::class.java) { AircraftCountermeasureDefinition(flaresPerBurst = 3) }
        assertThrows(IllegalArgumentException::class.java) { AircraftCountermeasureDefinition(flareEjectionSpeed = Double.NaN) }
    }
}
