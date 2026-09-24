package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AircraftBombPredictorTest {
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject

    @Test fun singleAndPairedStationsPredictTheirActualNextReleasePoint() {
        val single = json("""{"Id":"center","Position":[0,-1,-3]}""")
        val paired = json("""{"Id":"outer","Left":[-2,-1,0],"Right":[2,-1,0]}""")
        val bomb = json("""{"Category":"BOMB","Capacity":1}""")
        assertEquals(Vec3(0.0, -1.0, -3.0),
            AircraftBombPredictor.nextLaunchPosition(single, bomb, 1, 1))
        assertEquals(Vec3(-2.0, -1.0, 0.0),
            AircraftBombPredictor.nextLaunchPosition(paired, bomb, 2, 2))
        assertEquals(Vec3(2.0, -1.0, 0.0),
            AircraftBombPredictor.nextLaunchPosition(paired, bomb, 2, 1))
    }

    @Test fun loadedRackCopiesUseTheSameCurrentSlotAsServerRelease() {
        val paired = json("""{"Id":"outer","Left":[-2,-1,0],"Right":[2,-1,0]}""")
        val bomb = json("""{"Category":"BOMB","Capacity":1,
            "RackSpacing":[1,0.4,0.25],"LaunchOffset":[0,0,-0.2]}""")
        val expected = listOf(-2.5, 1.5, -1.5, 2.5, -2.0, 2.0)
        for (fired in expected.indices) {
            val next = AircraftBombPredictor.nextLaunchPosition(paired, bomb, 6, 6 - fired)
            assertEquals(Vec3(expected[fired], if(fired<4) -1.0 else -1.4, -0.2), next)
            assertEquals(AircraftPylonRacks.launchPosition(paired, bomb, 3, fired).add(0.0, 0.0, -0.2), next)
        }
    }

    @Test fun partiallyExpendedCapacityAndInternalBayCopiesKeepServerPlacement() {
        val paired = json("""{"Id":"pair","Left":[-2,0,0],"Right":[2,0,0]}""")
        val twoRoundBomb = json("""{"Category":"BOMB","Capacity":2,"RackSpacing":[1,0.4,0]}""")
        // Eight physical rounds: two paired positions, two complete rack copies,
        // and two rounds per position. The fifth fired round wraps to copy zero.
        assertEquals(Vec3(-2.5, 0.0, 0.0),
            AircraftBombPredictor.nextLaunchPosition(paired, twoRoundBomb, 8, 4))
        assertEquals(Vec3(2.5, 0.0, 0.0),
            AircraftBombPredictor.nextLaunchPosition(paired, twoRoundBomb, 8, 1))

        val bay = json("""{"Id":"bay","Position":[0,-2,1],"Internal":true}""")
        val bayBomb = json("""{"Category":"BOMB","Capacity":1,"LaunchOffset":[0,0,0.25]}""")
        assertEquals(Vec3(0.0, -2.0, 1.25),
            AircraftBombPredictor.nextLaunchPosition(bay, bayBomb, 32, 1))
        assertNull(AircraftBombPredictor.nextLaunchPosition(paired, twoRoundBomb, 8, 0))
        assertNull(AircraftBombPredictor.nextLaunchPosition(paired, twoRoundBomb, 8, 9))
        assertNull(AircraftBombPredictor.nextLaunchPosition(paired, twoRoundBomb, 7, 1))
    }
}
