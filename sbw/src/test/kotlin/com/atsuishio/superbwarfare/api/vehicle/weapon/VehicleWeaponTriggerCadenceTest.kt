package com.atsuishio.superbwarfare.api.vehicle.weapon

import net.minecraft.resources.ResourceLocation
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.floor

class VehicleWeaponTriggerCadenceTest {
    @Test
    fun `denied reload attempts never bank a catch-up burst`() {
        for (rpm in listOf(5, 850, 3400)) {
            val gun = state(rpm)
            for (tick in 0..100) {
                gun.tick(tick, true)
                assertTrue(gun.canAttempt())
                gun.consumeCredit()
                gun.recordRejected(ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, "Cannon"))
            }
            assertEquals(1, fire(gun, 100, true), "only one ready credit may survive denial")
            assertEquals(0, fire(gun, 100, true), "same-tick calls cannot mint another shot")
        }
    }

    @Test
    fun `TOW reload rejection preserves readiness but accepted launch keeps full cadence`() {
        val gun = state(5, grace = 0, repeat = false, preserve = true)
        for (tick in 0..239) {
            gun.tick(tick, true)
            assertTrue(gun.canAttempt(), "reload rejection must not consume a 240-tick launch interval")
            gun.consumeCredit()
            gun.recordRejected(ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, "Cannon"))
        }
        assertEquals(1, fire(gun, 240, true), "ready launcher fires immediately")
        gun.releaseEdge()
        for (tick in 241..479) assertEquals(0, fire(gun, tick, true))
        assertEquals(1, fire(gun, 480, true), "accepted launch retains the 5 RPM interval")
        assertEquals(0, fire(gun, 720, true), "held semi cannot launch twice")
    }

    @Test
    fun `projectile creation failure does not refund repeated expensive attempts`() {
        val gun = state(5, grace = 0, repeat = false, preserve = true)
        gun.tick(0, true)
        assertTrue(gun.canAttempt())
        gun.consumeCredit()
        gun.recordRejected(ShotResult.rejected(ShotRejectionReason.PROJECTILE_CREATION_FAILED, "Cannon"))
        assertEquals(0, fire(gun, 239, true))
        assertEquals(1, fire(gun, 240, true))
    }

    private fun state(rpm: Int, grace: Int = 4, repeat: Boolean = true, preserve: Boolean = false) =
        VehicleWeaponScheduler.RuntimeState(VehicleWeaponScheduleProfile(
            id = ResourceLocation("test", "trigger_cadence"),
            bulletRpm = rpm,
            eventRpm = rpm,
            projectilesPerEvent = 1,
            soundIntervalProjectiles = 1,
            repeatWhileHeld = repeat,
            preserveAcceptedCadenceAcrossPresses = preserve,
            releaseGraceTicks = grace,
            maxCatchUpEvents = maxOf(2, (rpm + 1199) / 1200),
        ))

    private fun fire(state: VehicleWeaponScheduler.RuntimeState, tick: Int, held: Boolean): Int {
        state.tick(tick, held)
        var shots = 0
        while (state.canAttempt()) {
            assertTrue(++shots <= 8, "scheduler exceeded its bounded event budget")
            state.consumeCredit()
            state.recordAccepted(tick)
        }
        return shots
    }

    @Test
    fun `semi packet release and repress between ticks rearms without resetting cadence`() {
        val gun = state(120, grace = 0, repeat = false)
        assertEquals(1, fire(gun, 0, true))
        repeat(20) {
            gun.releaseEdge()
            assertEquals(0, fire(gun, 0, true), "packet taps cannot refill shot credit")
        }
        assertEquals(0, fire(gun, 9, true))
        assertEquals(1, fire(gun, 10, true))
        assertEquals(0, fire(gun, 30, true), "held semi remains latched after one accepted shot")
        gun.releaseEdge()
        assertEquals(1, fire(gun, 31, true), "release packet rearms even without an unheld tick")
    }

    @Test
    fun `packet release edges retain automatic grace and cannot exceed configured cadence`() {
        for (rpm in listOf(120, 850, 3400)) for (repeatWhileHeld in listOf(false, true)) {
            val gun = state(rpm, grace = if (repeatWhileHeld) 4 else 0, repeat = repeatWhileHeld)
            var total = 0
            for (tick in 0..600) {
                repeat(3) {
                    gun.releaseEdge()
                    total += fire(gun, tick, true)
                    assertTrue(total <= 1 + floor(tick * rpm / 1200.0).toInt())
                }
            }
        }
    }

    @Test
    fun `release grace cannot accumulate a click burst`() {
        val gun = state(1000)
        assertEquals(1, fire(gun, 0, true))
        for (tick in 1..3) assertEquals(0, fire(gun, tick, false))
        assertEquals(1, fire(gun, 4, true), "a new click must not spend idle catch-up credit")
    }

    @Test
    fun `cooldown survives a complete release and same tick repress`() {
        val gun = state(120, grace = 0)
        assertEquals(1, fire(gun, 0, true))
        assertEquals(0, fire(gun, 1, false))
        repeat(20) {
            assertEquals(0, fire(gun, 1, true))
            assertEquals(0, fire(gun, 1, false))
        }
        assertEquals(0, fire(gun, 9, true))
        assertEquals(1, fire(gun, 10, true))
    }

    @Test
    fun `click patterns never exceed elapsed configured cadence`() {
        for (rpm in listOf(120, 550, 850, 1000, 1700, 3400, 6000)) {
            for (grace in listOf(0, 4)) for (period in listOf(2, 4, 6, 13)) {
                val gun = state(rpm, grace)
                var total = 0
                for (tick in 0..1200) {
                    total += fire(gun, tick, tick % period == 0)
                    val maximum = 1 + floor(tick * rpm / 1200.0).toInt()
                    assertTrue(total <= maximum,
                        "$rpm RPM, grace $grace, click period $period, tick $tick: $total > $maximum")
                }
            }
        }
    }

    @Test
    fun `idle tick skipping and weapon reselection ready one event only`() {
        val gun = state(6000, preserve = true)
        assertEquals(1, fire(gun, 0, true))
        assertEquals(0, fire(gun, 1, false))
        assertEquals(0, fire(gun, 6, false))
        assertTrue(gun.isStableIdle(), "ready idle weapons must not force scheduler tick work")
        assertEquals(1, fire(gun, 2000, true))
        assertEquals(5, fire(gun, 2001, true), "continuous high-RPM fire must resume at its authored rate")
    }

    @Test
    fun `short release at fractional rate retains unpaid cooldown`() {
        val gun = state(850, grace = 0)
        assertEquals(1, fire(gun, 0, true))
        assertEquals(0, fire(gun, 1, false))
        assertEquals(0, fire(gun, 1, true))
        assertEquals(1, fire(gun, 2, true))
    }

    @Test
    fun `semi auto still requires release and cannot bypass its cooldown`() {
        val gun = state(120, grace = 0, repeat = false)
        assertEquals(1, fire(gun, 0, true))
        assertEquals(0, fire(gun, 1, false))
        assertEquals(0, fire(gun, 2, true))
        assertEquals(1, fire(gun, 10, true))
        assertEquals(0, fire(gun, 30, true))
        assertEquals(0, fire(gun, 31, false))
        assertEquals(1, fire(gun, 32, true))
    }

    /**
     * Two seats (pilot and gunner) holding the same physical weapon: the scheduler runs one shared RuntimeState and
     * lets the second holder try only while the first could not use the slot (admitsSharedAttempt).
     */
    private fun fireShared(state: VehicleWeaponScheduler.RuntimeState, tick: Int, holders: Int): Int {
        state.tick(tick, holders > 0)
        val fired = HashSet<String>()
        var shots = 0
        for (seat in 0 until holders) {
            val key = VehicleWeaponScheduler.ScheduleKey(seat, seat * 3, "Cannon")
            if (!VehicleWeaponScheduler.admitsSharedAttempt(fired, key)) continue
            while (true) {
                if (!state.canAttempt()) { fired.add(VehicleWeaponScheduler.physicalWeapon(key)); break }
                assertTrue(++shots <= 8, "scheduler exceeded its bounded event budget")
                state.consumeCredit()
                state.recordAccepted(tick)
                fired.add(VehicleWeaponScheduler.physicalWeapon(key))
            }
        }
        return shots
    }

    @Test
    fun `two seats holding one weapon fire it at its own cadence`() {
        for (rpm in listOf(120, 550, 625, 1100, 4500)) {
            val one = state(rpm)
            val two = state(rpm)
            var single = 0
            var shared = 0
            for (tick in 0..1200) {
                single += fire(one, tick, true)
                shared += fireShared(two, tick, 2)
                assertTrue(shared <= 1 + floor(tick * rpm / 1200.0).toInt(), "$rpm RPM doubled at tick $tick")
            }
            assertEquals(single, shared, "$rpm RPM: two holders must fire exactly as one")
        }
    }

    @Test
    fun `a seat handover keeps the weapon cooldown`() {
        // pilot fires, lets go, and the gunner presses at once: the gun is still cycling
        val gun = state(120, grace = 0)
        assertEquals(1, fireShared(gun, 0, 1))
        gun.releaseEdge()
        for (tick in 1..9) assertEquals(0, fireShared(gun, tick, 1), "handover at tick $tick minted a shot")
        assertEquals(1, (10..11).sumOf { fireShared(gun, it, 1) }, "the next round comes at the gun's own cadence")
    }

    @Test
    fun `a retargeted profile keeps credits and heat`() {
        val bank = VehicleWeaponScheduler.RuntimeState(VehicleWeaponScheduleProfile(
            id = ResourceLocation("test", "bank"), bulletRpm = 600, eventRpm = 600, projectilesPerEvent = 1,
            soundIntervalProjectiles = 1, releaseGraceTicks = 0, preserveAcceptedCadenceAcrossPresses = true,
            maxCatchUpEvents = 2,
        ))
        assertEquals(1, fire(bank, 0, true))
        // the other seat resolves the same gun without the bank: same weapon, its cooldown carries over
        bank.retarget(VehicleWeaponScheduleProfile(
            id = ResourceLocation("test", "plain"), bulletRpm = 600, eventRpm = 600, projectilesPerEvent = 1,
            soundIntervalProjectiles = 1, releaseGraceTicks = 0, maxCatchUpEvents = 2,
        ))
        assertEquals(0, fire(bank, 1, true), "retarget must not hand out a fresh ready shot")
        assertEquals(1, fire(bank, 2, true))
    }

    @Test
    fun `a seat counts as holding only within its release grace`() {
        assertFalse(VehicleWeaponScheduler.heldWithinGrace(null, 10, 4))
        assertTrue(VehicleWeaponScheduler.heldWithinGrace(10, 10, 0))
        assertTrue(VehicleWeaponScheduler.heldWithinGrace(6, 10, 4))
        assertFalse(VehicleWeaponScheduler.heldWithinGrace(5, 10, 4))
    }
}
