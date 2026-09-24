package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.acos

class AircraftManualCommandTest {
    @Test fun manualCommandsPreserveSpeedAndLimitDiagonalTurn() {
        val forward = Vec3(0.0,0.0,3.0); val up = Vec3(0.0,1.0,0.0)
        val right = AircraftManualCommand.steer(forward,up,1,0,2.0)
        assertTrue(right.x < 0); assertEquals(0.0,right.y,1e-9)
        assertEquals(3.0,right.length(),1e-9)
        val diagonal = AircraftManualCommand.steer(forward,up,1,1,2.0)
        assertTrue(diagonal.x < 0 && diagonal.y > 0)
        assertEquals(2.0,Math.toDegrees(acos(diagonal.normalize().dot(forward.normalize()))),1e-8)
        assertEquals(forward,AircraftManualCommand.steer(forward,up,0,0,2.0))
        assertTrue(AircraftManualCommand.steer(up,up,1,0,2.0).lengthSqr().isFinite())
    }
    @Test fun expiredOrDifferentControllersCannotSteer() {
        val owner = UUID.randomUUID()
        assertTrue(AircraftManualCommand.fresh(owner,owner,100,104))
        assertFalse(AircraftManualCommand.fresh(owner,owner,100,105))
        assertFalse(AircraftManualCommand.fresh(owner,owner,100,99))
        assertFalse(AircraftManualCommand.fresh(owner,UUID.randomUUID(),100,101))
    }
    @Test fun commandStoreCannotMasqueradeAsLaserOrInfrared() {
        val store = JsonParser.parseString("""{"Schema":1,"Name":"AGM-12B","Category":"COMMAND_GUIDED",
            "Capacity":1,"MassKg":259,"LaunchGunProfile":"bvp:bullpup","ProjectileProfile":"bvp:bullpup",
            "CommandGuidance":{"Mode":"MCLOS"}}""").asJsonObject
        assertDoesNotThrow { AircraftArmamentRegistry.validate(store,true) }
        assertTrue(AircraftStoreWeapons.launchable(store))
        assertEquals("MCLOS",AircraftGuidanceLabels.mode(store))
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store.deepCopy().apply { addProperty("Category","LASER_GUIDED") },true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store.deepCopy().apply {
                getAsJsonObject("CommandGuidance").addProperty("Mode","INFRARED")
            },true)
        }
        val groups = AircraftWeaponGroups.groups(listOf(AircraftWeaponGroups.Mount("wing","bvp:bullpup","COMMAND_GUIDED",emptyList())))
        assertEquals(listOf("AircraftStore:wing"),groups.single().members)
    }
}
