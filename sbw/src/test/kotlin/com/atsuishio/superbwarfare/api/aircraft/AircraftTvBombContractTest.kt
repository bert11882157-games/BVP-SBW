package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftTvBombContractTest {
    private fun bomb()=JsonParser.parseString("""{
        "Schema":1,"Name":"TV bomb","Category":"BOMB","MassKg":1100,
        "Bomb":{"Mode":"TV","Gravity":0.08,"DragMultiplier":1,"TurnDegreesPerTick":2,"BlastRadius":20,"BlastDamage":500},
        "Guidance":{"Mode":"GROUND_INFRARED","Presentation":"TV","LockTicks":60,"Range":600,"ConeDegrees":25,"CountermeasureVulnerability":0}
    }""").asJsonObject

    @Test fun tvBombRequiresGroundLockAndRemainsUnpowered() {
        assertDoesNotThrow { AircraftArmamentRegistry.validate(bomb(),true) }
        assertEquals("TV",AircraftGuidanceLabels.mode(bomb()))
        assertEquals("Electro-optical homing",AircraftGuidanceLabels.description("TV"))
        for(change in listOf<(JsonObject)->Unit>(
            { it.remove("Guidance") },
            { it.getAsJsonObject("Guidance").addProperty("Mode","INFRARED") },
            { it.getAsJsonObject("Guidance").addProperty("LockTicks",0) },
            { it.getAsJsonObject("Guidance").addProperty("Presentation","FIRE_AND_FORGET") },
            { it.add("Flight",JsonObject()) },
            { it.getAsJsonObject("Bomb").addProperty("Mode","GPS") }
        )) assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(bomb().also(change),true)
        }
    }

    @Test fun primaryTvBombShowsGroundTrackingSquareInsteadOfSecondaryAamSeeker() {
        val bomb=AircraftSeekerDisplayPolicy.Channel("GBU15","BOMB","GROUND_INFRARED","PRIMARY")
        val aam=AircraftSeekerDisplayPolicy.Channel("AIM9","AIR_TO_AIR","INFRARED","SECONDARY")
        assertSame(bomb,AircraftSeekerDisplayPolicy.choose(listOf(bomb,aam)))
    }
}
