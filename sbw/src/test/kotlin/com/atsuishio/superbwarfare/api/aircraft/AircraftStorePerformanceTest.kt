package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance.Figure
import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance.GunFigures
import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance.PropulsionFigures
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStorePerformanceTest {
    private class FakeSources(val guns: Map<String, GunFigures> = emptyMap(),
                              val profiles: Map<String, PropulsionFigures> = emptyMap()) : AircraftStorePerformance.Sources {
        override fun gun(id: String) = guns[id]
        override fun propulsion(profileId: String) = profiles[profileId]
    }
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject
    private fun of(text: String, sources: AircraftStorePerformance.Sources = FakeSources()) =
        AircraftStorePerformance.of(json(text), sources)

    @Test fun ffaMissileReadsItsFlightBlockAndFlightCharge() {
        val stats = of("""{"Category":"AIR_TO_AIR","Guidance":{},"TntEquivalentKg":3,
            "Flight":{"InitialSpeed":0.5,"MaxSpeed":9,"AccelerationPerTick":0.1,"TurnDegreesPerSecond":70,
            "Damage":50,"BlastRadius":4,"TntEquivalentKg":15.56}}""")!!
        assertEquals(Figure.of(70.0), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.of(15.56), stats.explosiveKgTnt)
        assertEquals(Figure.of(0.1), stats.accelerationBlocksPerTickSquared)
        assertEquals(Figure.of(9.0), stats.topSpeedBlocksPerTick)
    }

    @Test fun guidedMissileWithoutFlightBlockFliesOnTheBallisticsModDefaults() {
        val stats = of("""{"Category":"AIR_TO_AIR","Guidance":{"Mode":"INFRARED"},"TntEquivalentKg":1.15}""")!!
        assertEquals(Figure.none(AircraftStorePerformance.FFA_DEFAULT), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.none(AircraftStorePerformance.FFA_DEFAULT), stats.topSpeedBlocksPerTick)
        assertEquals(Figure.of(1.15), stats.explosiveKgTnt)
    }

    @Test fun storesThatReleaseNothingHaveNoFigures() {
        assertNull(of("""{"Category":"VISUAL_ONLY"}"""))
        assertNull(of("""{"Category":"TARGETING_POD","Pod":{}}"""))
        assertNull(of("""{"Category":"MISSILE_LAUNCHER"}"""))
        assertNull(of("""{"Category":"AIR_TO_AIR"}"""), "visual-only air-to-air shape")
        assertNull(of("""{"Name":"no category"}"""))
    }

    @Test fun dumbBombIsUnguidedUnpoweredAndCarriesItsBombCharge() {
        val stats = of("""{"Category":"BOMB","Bomb":{"Mode":"DUMB","TurnDegreesPerTick":0,"TntEquivalentKg":675}}""")!!
        assertEquals(Figure.none(AircraftStorePerformance.UNGUIDED), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.of(675.0), stats.explosiveKgTnt)
        assertEquals(Figure.none(AircraftStorePerformance.UNPOWERED), stats.accelerationBlocksPerTickSquared)
        assertEquals(Figure.none(AircraftStorePerformance.FREE_FALL), stats.topSpeedBlocksPerTick)
    }

    @Test fun guidedBombTurnRateIsPerSecondAndClusterChargeCountsEveryBomblet() {
        val laser = of("""{"Category":"BOMB","Bomb":{"Mode":"LASER","TurnDegreesPerTick":2,"TntEquivalentKg":0}}""")!!
        assertEquals(Figure.of(40.0), laser.turnRateDegreesPerSecond)
        assertEquals(Figure.none(AircraftStorePerformance.NO_CHARGE), laser.explosiveKgTnt)
        val cluster = of("""{"Category":"BOMB","Bomb":{"Mode":"DUMB","TurnDegreesPerTick":0,
            "Cluster":{"Count":40,"BombletTntEquivalentKg":0.25}}}""")!!
        assertEquals(10.0, cluster.explosiveKgTnt.value!!, 1e-9)
    }

    @Test fun laserMissileUsesItsLaunchGunChargeAndAverageBurn() {
        val sources = FakeSources(
            guns = mapOf("bvp:kh25" to GunFigures(2.0, 119.04, "bvp:kh25")),
            profiles = mapOf("bvp:kh25" to PropulsionFigures(2.0, 10.0, 40, 180.0)))
        val stats = of("""{"Category":"LASER_GUIDED","LaunchGunProfile":"bvp:kh25","ProjectileProfile":"bvp:kh25"}""", sources)!!
        assertEquals(Figure.of(180.0), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.of(119.04), stats.explosiveKgTnt)
        assertEquals(0.2, stats.accelerationBlocksPerTickSquared.value!!, 1e-12)
        assertEquals(Figure.of(10.0), stats.topSpeedBlocksPerTick)
    }

    @Test fun rocketPodIsUnguidedAndFallsBackToMuzzleVelocityWithoutPropulsion() {
        val sources = FakeSources(guns = mapOf("bvp:s8" to GunFigures(6.0, 1.2, null)))
        val stats = of("""{"Category":"ROCKET_POD","GunProfile":"bvp:s8","LaunchGunProfile":"bvp:other"}""", sources)!!
        assertEquals(Figure.none(AircraftStorePerformance.UNGUIDED), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.of(1.2), stats.explosiveKgTnt)
        assertEquals(Figure.none(AircraftStorePerformance.UNPOWERED), stats.accelerationBlocksPerTickSquared)
        assertEquals(Figure.of(6.0), stats.topSpeedBlocksPerTick)
    }

    @Test fun unknownProfilesAreReportedAsNotAuthoredRatherThanZero() {
        val stats = of("""{"Category":"COMMAND_GUIDED","LaunchGunProfile":"bvp:missing"}""")!!
        assertEquals(Figure.none(AircraftStorePerformance.NOT_AUTHORED), stats.turnRateDegreesPerSecond)
        assertEquals(Figure.none(AircraftStorePerformance.NOT_AUTHORED), stats.accelerationBlocksPerTickSquared)
        assertEquals(Figure.none(AircraftStorePerformance.NOT_AUTHORED), stats.topSpeedBlocksPerTick)
        assertEquals(Figure.none(AircraftStorePerformance.NO_CHARGE), stats.explosiveKgTnt)
    }

    @Test fun burnAccelerationNeverGoesNegativeOrDividesByZero() {
        assertEquals(0.0, AircraftStorePerformance.burnAcceleration(PropulsionFigures(5.0, 4.0, 10, 30.0)))
        assertEquals(3.0, AircraftStorePerformance.burnAcceleration(PropulsionFigures(1.0, 4.0, 0, 30.0)))
    }
}
