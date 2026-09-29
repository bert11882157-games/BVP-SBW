package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftRoundConsolidationCadenceTest {
    private val key = VehicleWeaponScheduler.ScheduleKey(0, 0, "Gun")

    @Test
    fun `no round is consolidated any more (owner 2026-09-29)`() {
        val c = AircraftRoundConsolidation
        assertFalse(c.ENABLED)
        assertEquals(1, c.scheduleWeight(aircraft = true, rpm = 6000, automatic = true, gunRound = true),
            "the M61 fires every round")
        assertEquals(1, c.scheduleWeight(true, 701, true, true))
        assertEquals(1, c.scheduleWeight(true, 700, true, true), "exactly 700 RPM keeps every round")
        assertEquals(1, c.scheduleWeight(false, 6000, true, true), "ground vehicles are unchanged")
        assertEquals(1, c.scheduleWeight(true, 6000, false, true), "semi-automatic clicks are unchanged")
        assertEquals(1, c.scheduleWeight(true, 6000, true, false), "rockets and stores are unchanged")

        assertTrue(c.isAircraftType(VehicleType.AIRPLANE))
        assertTrue(c.isAircraftType(VehicleType.HELICOPTER))
        for (type in listOf(VehicleType.TANK, VehicleType.APC, VehicleType.AA, VehicleType.DRONE, VehicleType.CAR, null))
            assertFalse(c.isAircraftType(type), "$type")

        assertTrue(c.isGunRound("superbwarfare:small_cannon_shell"))
        assertTrue(c.isGunRound(" superbwarfare:projectile "))
        for (id in listOf("superbwarfare:small_rocket", "berts_vehicle_pack:s8ko_rocket", "ray", "empty", "", null))
            assertFalse(c.isGunRound(id), "$id")
    }

    @Test
    fun `consolidated profile halves event rate and catch-up budget but keeps the real RPM`() {
        val m61 = profile(6000).consolidated(AircraftRoundConsolidation.WEIGHT)
        assertEquals(3000, m61.eventRpm)
        assertEquals(6000, m61.bulletRpm, "HUD and snapshot RPM stay the real rate")
        assertEquals(2, m61.roundWeight)
        assertEquals(2, m61.roundsPerEvent)
        assertEquals(3, m61.maxCatchUpEvents, "2.5 events per tick need a three-event budget")

        val odd = profile(1701).consolidated(2)
        assertEquals(851, odd.eventRpm)
        assertEquals(1701, odd.bulletRpm)
        assertEquals(1, odd.maxCatchUpEvents)

        val plain = profile(6000)
        assertSame(plain, plain.consolidated(1))
        assertSame(m61, m61.consolidated(2), "consolidation never compounds")
        assertEquals(1, plain.roundWeight)
        assertThrows(IllegalArgumentException::class.java) { plain.copy(roundWeight = 3) }
    }

    @Test
    fun `F-16 M61 spawns 50 rounds per second that stand for the authored 100`() {
        for (rpm in listOf(750, 1200, 1701, 1800, 3400, 4500, 6000, 9600)) {
            val consolidated = profile(rpm).consolidated(2)
            val gun = VehicleWeaponScheduler.RuntimeState(consolidated)
            var events = 0
            for (tick in 0..1200) {
                gun.tick(tick, true)
                var inTick = 0
                while (gun.canAttempt()) {
                    assertTrue(++inTick <= consolidated.maxCatchUpEvents)
                    gun.consumeCredit()
                    gun.recordAccepted(tick)
                    events++
                }
            }
            val expected = AircraftRoundConsolidation.eventRpm(rpm, 2)
            assertTrue(events in expected..expected + 1, "$rpm RPM fired $events events")
            assertTrue(events * 2 in rpm..rpm + 3, "$rpm RPM represented ${events * 2} rounds")
            assertEquals(rpm, gun.snapshot(key).maxRpm)
        }
    }

    @Test
    fun `scheduled sound cadence and heat per second match the unconsolidated gun`() {
        val yakB = profile(4500, soundInterval = 14)
        assertEquals(minuteSounds(yakB).toDouble(), minuteSounds(yakB.consolidated(2)).toDouble(), 2.0)

        val heated = profile(1200, heat = VehicleWeaponHeatPolicy(100, 200, 40))
        val ordinaryTick = overheatTick(heated)
        val consolidatedTick = overheatTick(heated.consolidated(2))
        assertTrue(ordinaryTick in 95..105, "ordinary gun overheated at $ordinaryTick")
        assertTrue(kotlin.math.abs(ordinaryTick - consolidatedTick) <= 2,
            "heat per second changed: $ordinaryTick vs $consolidatedTick")
    }

    @Test
    fun `fixed banks and gun pods accept consolidated members at their authored rate`() {
        assertTrue(VehicleFixedGunBanks.matchesAuthoredRate(profile(6000), 6000))
        assertTrue(VehicleFixedGunBanks.matchesAuthoredRate(profile(6000).consolidated(2), 6000))
        assertTrue(VehicleFixedGunBanks.matchesAuthoredRate(profile(1701).consolidated(2), 1701))
        assertFalse(VehicleFixedGunBanks.matchesAuthoredRate(profile(6000).consolidated(2), 3000))
        assertFalse(VehicleFixedGunBanks.matchesAuthoredRate(profile(6000), 3000))
        assertFalse(VehicleFixedGunBanks.matchesAuthoredRate(profile(6000).copy(roundWeight = 2), 6000),
            "a weighted profile firing at the full event rate would double the rounds")
        assertFalse(VehicleFixedGunBanks.matchesAuthoredRate(profile(3000).copy(bulletRpm = 6000), 6000))
        assertEquals(3, VehicleFixedGunBanks.eventCapacity(profile(6000).consolidated(2).eventRpm))
    }

    @Test
    fun `weighted rounds consume the same ammunition and the last odd round fires alone`() {
        val c = AircraftRoundConsolidation
        assertEquals(2, c.affordableWeight(2, 1, 512))
        assertEquals(2, c.affordableWeight(2, 1, 2))
        assertEquals(1, c.affordableWeight(2, 1, 1))
        assertEquals(1, c.affordableWeight(2, 2, 3))
        assertEquals(2, c.affordableWeight(2, 2, 4))
        assertEquals(1, c.affordableWeight(1, 1, 512))
        assertEquals(2, c.affordableWeight(2, 0, 0), "costless guns keep their weight")
        assertEquals(1, c.extraAmmo(2, 1))
        assertEquals(0, c.extraAmmo(1, 1))
        assertEquals(0, c.extraAmmo(2, 0))
        assertEquals(3, c.extraAmmo(2, 3))

        for (magazine in listOf(512, 511, 1, 2, 125)) {
            var ammo = magazine
            var events = 0
            var rounds = 0
            while (ammo >= 1) {
                val weight = c.affordableWeight(2, 1, ammo)
                ammo -= 1 + c.extraAmmo(weight, 1)
                rounds += weight
                events++
            }
            assertEquals(0, ammo)
            assertEquals(magazine, rounds, "every round of the magazine is delivered")
            assertEquals((magazine + 1) / 2, events)
        }
    }

    @Test
    fun `request and launch scopes are restored after nested and failing shots`() {
        val c = AircraftRoundConsolidation
        assertEquals(1, c.requestedWeight())
        assertEquals(1, c.launchWeight())
        c.withRequestedWeight(2) {
            assertEquals(2, c.requestedWeight())
            c.withLaunchWeight(1) { assertEquals(1, c.launchWeight()) }
            c.withLaunchWeight(5) { assertEquals(2, c.launchWeight()) }
            assertEquals(1, c.launchWeight())
        }
        assertEquals(1, c.requestedWeight())
        assertThrows(IllegalStateException::class.java) {
            c.withLaunchWeight<Unit>(2) { throw IllegalStateException("spawn failed") }
        }
        assertEquals(1, c.launchWeight())
    }

    private fun profile(rpm: Int, soundInterval: Int = 1, heat: VehicleWeaponHeatPolicy? = null) =
        VehicleWeaponScheduleProfile(
            id = ResourceLocation("test", "consolidation"),
            bulletRpm = rpm,
            eventRpm = rpm,
            projectilesPerEvent = 1,
            soundIntervalProjectiles = soundInterval,
            heatPolicy = heat,
            maxCatchUpEvents = maxOf(2, (rpm + 1199) / 1200),
        )

    private fun minuteSounds(profile: VehicleWeaponScheduleProfile): Int {
        val gun = VehicleWeaponScheduler.RuntimeState(profile)
        var sounds = 0
        for (tick in 0..1200) {
            gun.tick(tick, true)
            while (gun.canAttempt()) {
                gun.consumeCredit()
                if (gun.recordAccepted(tick)) sounds++
            }
        }
        return sounds
    }

    private fun overheatTick(profile: VehicleWeaponScheduleProfile): Int {
        val gun = VehicleWeaponScheduler.RuntimeState(profile)
        for (tick in 0..1200) {
            gun.tick(tick, true)
            while (gun.canAttempt()) {
                gun.consumeCredit()
                gun.recordAccepted(tick)
                if (gun.snapshot(key).overheatTicks > 0) return tick
            }
        }
        fail<Unit>("${profile.eventRpm} events per minute never overheated")
        return -1
    }
}
