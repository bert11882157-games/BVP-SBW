package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.permitsLandingGearShot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreMountingTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun store(): JsonObject = json("""{"Schema":1,"Name":"Mk 82","Category":"BOMB",
        "Model":"test:custom_geo/aircraft_stores/mk82.geo.json","Texture":"test:textures/aircraft_stores/mk82.png",
        "Capacity":1,"MassKg":227,"Bomb":{"Mode":"DUMB","Gravity":0.08,"DragMultiplier":1,
        "TurnDegreesPerTick":0,"BlastRadius":7,"BlastDamage":450}}""")
    private fun aircraft(): JsonObject = json("""{"Schema":1,"Name":"Fixture","MaxPayloadKg":2000,
        "Pairs":[{"Id":"wing","Name":"Wing pylon","Left":[-2,1,0],"Right":[2,1,0],
        "AllowedStores":["test:mk82"]}],
        "Singles":[{"Id":"centerline","Name":"Centerline","Position":[0,0.9,1],
        "AllowedStores":["test:mk82"]}]}""")

    @Test fun mountAnchorIsReadInModelBlocksAndDefaultsToTheOrigin() {
        assertEquals(Vec3.ZERO, AircraftStoreMountAnchor.read(store()))
        val anchored = store().apply { add(AircraftStoreMountAnchor.KEY, JsonParser.parseString("[0,0.0851,0.3988]")) }
        assertDoesNotThrow { AircraftArmamentRegistry.validate(anchored, true) }
        val anchor = AircraftStoreMountAnchor.read(anchored)
        assertEquals(0.0851, anchor.y, 1e-9)
        assertEquals(0.3988, anchor.z, 1e-9)
    }

    @Test fun invalidMountAnchorsAreRejected() {
        for (bad in listOf("[0,1]", "[0,\"top\",0]", "[0,9,0]", "0.5")) assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store().apply { add(AircraftStoreMountAnchor.KEY, JsonParser.parseString(bad)) }, true)
        }
        // An anchor positions an authored model; an item-rendered store has none.
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store().apply {
                remove("Model"); add(AircraftStoreMountAnchor.KEY, JsonParser.parseString("[0,0.1,0]"))
            }, true)
        }
    }

    @Test fun gearInterlockIsAnOptionalBooleanMountKey() {
        assertDoesNotThrow { AircraftArmamentRegistry.validate(aircraft(), false) }
        val gated = aircraft().apply {
            getAsJsonArray("Singles")[0].asJsonObject.addProperty(AircraftMountGearInterlock.KEY, true)
        }
        assertDoesNotThrow { AircraftArmamentRegistry.validate(gated, false) }
        assertTrue(AircraftMountGearInterlock.required(gated.getAsJsonArray("Singles")[0].asJsonObject))
        assertFalse(AircraftMountGearInterlock.required(gated.getAsJsonArray("Pairs")[0].asJsonObject))
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(aircraft().apply {
                getAsJsonArray("Pairs")[0].asJsonObject.addProperty(AircraftMountGearInterlock.KEY, "yes")
            }, false)
        }
    }

    @Test fun gatedStationsReleaseOnlyWithTheGearFullyStowed() {
        assertTrue(permitsLandingGearShot(false, gearUp = false, retraction = 0f))
        assertFalse(permitsLandingGearShot(true, gearUp = false, retraction = 0f))
        assertFalse(permitsLandingGearShot(true, gearUp = true, retraction = 0.6f))
        assertFalse(permitsLandingGearShot(true, gearUp = true, retraction = Float.NaN))
        assertTrue(permitsLandingGearShot(true, gearUp = true, retraction = 1f))
    }
}
