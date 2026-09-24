package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreModelForwardTest {
    private fun store(): JsonObject = JsonParser.parseString("""{"Schema":1,"Name":"FAB-500","Category":"BOMB",
        "Model":"test:custom_geo/aircraft_stores/fab_500.geo.json","Texture":"test:textures/aircraft_stores/fab_500.png",
        "Capacity":1,"MassKg":500,"Bomb":{"Mode":"DUMB","Gravity":0.08,"DragMultiplier":1,
        "TurnDegreesPerTick":0,"BlastRadius":8,"BlastDamage":500}}""").asJsonObject

    @Test fun onlyAPositiveZNoseTurnsTheMountedModel() {
        assertEquals(180f, AircraftStoreModelForward.mountYawDegrees(AircraftStoreModelForward.POSITIVE_Z))
        assertEquals(0f, AircraftStoreModelForward.mountYawDegrees(AircraftStoreModelForward.NEGATIVE_Z))
        assertEquals(0f, AircraftStoreModelForward.mountYawDegrees(AircraftStoreModelForward.DEFAULT))
    }

    @Test fun modeledStoresAcceptBothDesignationsAndOmission() {
        assertDoesNotThrow { AircraftArmamentRegistry.validate(store(), true) }
        for (forward in AircraftStoreModelForward.values) assertDoesNotThrow {
            AircraftArmamentRegistry.validate(store().apply { addProperty(AircraftStoreModelForward.KEY, forward) }, true)
        }
    }

    @Test fun invalidDesignationsAreRejected() {
        for (bad in listOf("Z", "+z", "-Y", "")) assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store().apply { addProperty(AircraftStoreModelForward.KEY, bad) }, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store().apply { addProperty(AircraftStoreModelForward.KEY, 180) }, true)
        }
        // A designation describes an authored model; an item-rendered store has none.
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store().apply {
                remove("Model"); addProperty(AircraftStoreModelForward.KEY, AircraftStoreModelForward.POSITIVE_Z)
            }, true)
        }
    }
}
