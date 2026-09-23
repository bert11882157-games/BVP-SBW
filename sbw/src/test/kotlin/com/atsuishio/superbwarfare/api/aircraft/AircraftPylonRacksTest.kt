package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftPylonRacksTest {
    @Test fun physicalLimitsAndMassPreventOversizedRacks() {
        fun limit(category: String = "BOMB", capacity: Int = 1, store: Int = 12, mass: Double = 250.0) =
            AircraftPylonRacks.maxCopies(12, 4, store, category, capacity, mass, 1000.0)
        assertEquals(4, limit())
        assertEquals(2, limit(capacity = 2))
        assertEquals(1, limit(store = 1, mass = 1000.0))
        assertEquals(1, limit(category = "CRUISE"))
        assertEquals(1, limit(category = "ROCKET_POD"))
        assertEquals(2, limit(category = "AIR_TO_AIR", store = 2, mass = 85.0))
    }

    @Test fun pairedLaunchesAlternateSidesAndConsumeEachRackPosition() {
        val mount = JsonParser.parseString("""{"Id":"pair","Left":[-4,0,0],"Right":[4,0,0]}""").asJsonObject
        val store = JsonParser.parseString("""{"Capacity":1,"MassKg":250}""").asJsonObject
        val launches = (0..7).map { AircraftPylonRacks.launchPosition(mount, store, 4, it) }
        assertEquals(8, launches.toSet().size)
        launches.chunked(2).forEach { assertEquals(8.0, it[1].x - it[0].x, 1e-9) }
        assertTrue(launches.take(6).all { it.y == 0.0 })
        assertEquals(-0.4, launches[6].y, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { AircraftPylonRacks.launchPosition(mount, store, 4, 8) }
        val definition = JsonParser.parseString("""{"Pairs":[${mount}]}""").asJsonObject
        assertEquals(2000.0, AircraftArmamentRegistry.loadoutMassKg(definition, mapOf("pair" to store), mapOf("pair" to 4)))
        assertEquals(Vec3.ZERO, AircraftPylonRacks.offset(0, 1, Vec3(0.6, 0.4, 0.0)))
    }
}
