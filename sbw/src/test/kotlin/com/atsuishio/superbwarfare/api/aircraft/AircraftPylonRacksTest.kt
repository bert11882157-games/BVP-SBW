package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftPylonRacksTest {
    @Test fun loadoutEditsKeepExistingCopiesButCannotChooseMore() {
        assertEquals(3, AircraftPylonRacks.fixedSelectionCopies("bomb", "bomb", 3, null))
        assertEquals(3, AircraftPylonRacks.fixedSelectionCopies("bomb", "bomb", 3, 3))
        assertEquals(1, AircraftPylonRacks.fixedSelectionCopies("bomb", "missile", 3, null))
        assertEquals(2, AircraftPylonRacks.fixedSelectionCopies("bomb", "bomb_pair", 3, null, 2))
        assertEquals(3, AircraftPylonRacks.fixedSelectionCopies(null, "bomb_triple", 1, null, 3))
        assertThrows(IllegalArgumentException::class.java) {
            AircraftPylonRacks.fixedSelectionCopies("bomb", "bomb", 3, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftPylonRacks.fixedSelectionCopies("bomb", "missile", 3, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftPylonRacks.fixedSelectionCopies("bomb", "bomb_pair", 3, 3, 2)
        }
    }

    @Test fun pylonMassAndWholeAircraftBudgetAreIndependentAndBayUsesOneReleasePoint() {
        assertEquals(4, AircraftPylonRacks.maxCopies(12,12,12,"BOMB",1,250.0,1000.0))
        assertEquals(10, AircraftPylonRacks.maxCopies(12,12,12,"BOMB",1,100.0,1000.0))
        assertEquals(1, AircraftPylonRacks.maxCopies(12,12,12,"BOMB",1,1000.0,1000.0))
        val mounts = (1..6).joinToString(",") { """{"Id":"p$it","Position":[0,0,$it]}""" }
        val definition = JsonParser.parseString("""{"Singles":[$mounts],"MaxPayloadKg":4000}""").asJsonObject
        val bomb = JsonParser.parseString("""{"Category":"BOMB","MassKg":250,"Capacity":1}""").asJsonObject
        val four = (1..4).associate { "p$it" to bomb }
        assertEquals(4000.0, AircraftArmamentRegistry.loadoutMassKg(definition, four, four.keys.associateWith { 4 }))
        assertTrue(AircraftArmamentRegistry.loadoutMassKg(definition, four + ("p5" to bomb),
            four.keys.associateWith { 4 }) > definition["MaxPayloadKg"].asDouble)
        val bay = JsonParser.parseString("""{"Id":"bay","Position":[0,-1,0],"Internal":true,"MaxPylonMassKg":15000}""").asJsonObject
        val count = AircraftPylonRacks.maxCopies(definition, bay, bomb)
        assertEquals(60, count)
        assertEquals(1, (0 until count).map { AircraftPylonRacks.launchPosition(bay,bomb,count,it) }.toSet().size)
    }
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

    @Test fun fixedBombChoicesAndZeroBlastClusterHaveDistinctValidatedContracts() {
        val rack = JsonParser.parseString("""{"Schema":1,"Name":"FAB-100 x2","Category":"BOMB",
            "Capacity":1,"MassKg":100,"MaxPerPylon":2,"FixedRackCount":2,"Bomb":{
            "Mode":"DUMB","Gravity":0.08,"DragMultiplier":1,"TurnDegreesPerTick":0,
            "BlastRadius":14,"BlastDamage":200}}""").asJsonObject
        assertDoesNotThrow { AircraftArmamentRegistry.validate(rack, true) }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(rack.deepCopy().apply { addProperty("MaxPerPylon", 1) }, true)
        }
        val cluster = JsonParser.parseString("""{"Schema":1,"Name":"RBK-250","Category":"BOMB",
            "Capacity":1,"MassKg":250,"Bomb":{"Mode":"DUMB","Gravity":0.08,
            "DragMultiplier":1,"TurnDegreesPerTick":0,"BlastRadius":0,"BlastDamage":0,
            "Cluster":{"Count":12,"ReleaseHeight":12,"SpreadSpeed":0.35,
            "BombletDamage":80,"BombletRadius":1.5,"LifetimeTicks":100}}}""").asJsonObject
        assertDoesNotThrow { AircraftArmamentRegistry.validate(cluster, true) }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(cluster.deepCopy().apply {
                getAsJsonObject("Bomb").addProperty("BlastDamage", 500)
            }, true)
        }
    }
}
