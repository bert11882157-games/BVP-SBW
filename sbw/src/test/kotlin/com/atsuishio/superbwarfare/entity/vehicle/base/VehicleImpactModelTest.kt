package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleImpactModel.Body
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleImpactModel.Zone
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Owner 2026-09-29: collisions must not catapult vehicles, and must deal size-aware damage. */
class VehicleImpactModelTest {
    private val east = Vec3(1.0, 0.0, 0.0)

    /** A moving east (-x of B), B ahead: normal from B toward A points west. */
    private fun headOn(a: Body, b: Body, depth: Double = 0.0) = VehicleImpactModel.resolve(a, b, Vec3(-1.0, 0.0, 0.0), depth)

    private fun momentum(body: Body, delta: Vec3) = body.velocity.add(delta).scale(body.mass)

    @Test
    fun momentumIsConservedAndNobodyIsLaunched() {
        // T-72 at 0.8 b/t (58 km/h) into a parked 3 t pickup: the old code gave the pickup 2.56 b/t.
        val tank = Body(44.5, east.scale(0.8), false)
        val pickup = Body(3.0, Vec3.ZERO, false)
        val outcome = headOn(tank, pickup)
        val before = tank.velocity.scale(tank.mass).add(pickup.velocity.scale(pickup.mass))
        val after = momentum(tank, outcome.deltaA).add(momentum(pickup, outcome.deltaB))
        assertEquals(0.0, before.subtract(after).length(), 1e-9)
        val pickupSpeed = pickup.velocity.add(outcome.deltaB).length()
        assertTrue(pickupSpeed <= 0.8 * (1 + VehicleImpactModel.RESTITUTION) + 1e-9, "pickup at $pickupSpeed b/t")
        assertEquals(0.0, outcome.deltaB.y, 1e-12)
        assertTrue(outcome.damageB > 0.2 && outcome.damageB < 1.0, "pickup damage ${outcome.damageB}")
        assertTrue(outcome.damageA < 0.01, "tank damage ${outcome.damageA}")
    }

    @Test
    fun cappedImpulseStillConservesMomentum() {
        val tank = Body(57.0, east.scale(2.5), false)
        val car = Body(1.0, Vec3.ZERO, false)
        val outcome = headOn(tank, car)
        assertTrue(outcome.deltaB.length() <= VehicleImpactModel.MAX_GROUND_DELTA + 1e-9)
        val lost = outcome.deltaA.scale(tank.mass)
        val gained = outcome.deltaB.scale(car.mass)
        assertEquals(0.0, lost.add(gained).length(), 1e-9)
    }

    @Test
    fun repeatedContactNeverOutrunsThePusher() {
        // A 57 t vehicle at 1.5 b/t shoving a 1 t car for 40 ticks while overlapping: the car ends up moving with it,
        // not faster than the bounce allows.
        var tank = east.scale(1.5)
        var car = Vec3.ZERO
        repeat(40) {
            val outcome = headOn(Body(57.0, tank, false), Body(1.0, car, false), depth = 0.5)
            tank = tank.add(outcome.deltaA)
            car = car.add(outcome.deltaB)
        }
        assertTrue(car.x <= tank.x * (1 + VehicleImpactModel.RESTITUTION) + VehicleImpactModel.SEPARATION_SPEED + 1e-9,
            "car ${car.x}, tank ${tank.x}")
        assertTrue(car.x <= 1.5 * 1.3, "car ${car.x}")
    }

    @Test
    fun protectedHeavyVehicleIsFlagged() {
        val outcome = headOn(Body(12.46, east.scale(9.0), true), Body(169.2, east.scale(-6.0), true))
        assertTrue(outcome.protectedB)
        assertFalse(outcome.protectedA)
        val equal = headOn(Body(25.8, east.scale(5.0), true), Body(25.8, east.scale(-5.0), true))
        assertFalse(equal.protectedA || equal.protectedB)
    }

    @Test
    fun separatingVehiclesAreLeftAlone() {
        val a = Body(40.0, east.scale(-0.5), false)
        val b = Body(40.0, Vec3.ZERO, false)
        val outcome = headOn(a, b)
        assertEquals(Vec3.ZERO, outcome.deltaA)
        assertEquals(Vec3.ZERO, outcome.deltaB)
        assertFalse(outcome.damages)
    }

    @Test
    fun overlappingVehiclesAreEasedApartNotThrown() {
        // Two parked 57 t howitzers found 1.5 blocks inside each other: the old anti-stacking shove gave 2.1 b/t,
        // 1.4 of it upward, every 4 ticks.
        val a = Body(57.0, Vec3.ZERO, false)
        val b = Body(57.0, Vec3.ZERO, false)
        val outcome = headOn(a, b, depth = 1.5)
        val separation = outcome.deltaA.subtract(outcome.deltaB).dot(Vec3(-1.0, 0.0, 0.0))
        assertEquals(VehicleImpactModel.SEPARATION_SPEED, separation, 1e-9)
        assertEquals(0.0, outcome.deltaA.y, 1e-12)
        assertFalse(outcome.damages)
        // Already separating at the target speed: nothing more is added, so it cannot accumulate.
        val moving = headOn(Body(57.0, east.scale(-0.05), false), Body(57.0, east.scale(0.05), false), depth = 1.5)
        assertEquals(Vec3.ZERO, moving.deltaA)
    }

    @Test
    fun parkingBumpDoesNoDamage() {
        val outcome = headOn(Body(44.5, east.scale(0.12), false), Body(44.5, Vec3.ZERO, false))
        assertFalse(outcome.damages)
        assertTrue(outcome.closingSpeed > 0.0)
    }

    @Test
    fun equalTanksHeadOnTakeEqualModerateDamage() {
        val outcome = headOn(Body(44.5, east.scale(0.5), false), Body(44.5, east.scale(-0.5), false))
        assertEquals(outcome.damageA, outcome.damageB, 1e-12)
        assertTrue(outcome.damageA in 0.15..0.35, "damage ${outcome.damageA}")
        assertFalse(outcome.lethalA || outcome.lethalB)
    }

    @Test
    fun comparableAircraftHeadOnDestroysBoth() {
        // Two Su-27s at 100 m/s each.
        val outcome = headOn(Body(25.8, east.scale(5.0), true), Body(25.8, east.scale(-5.0), true))
        assertTrue(outcome.lethalA && outcome.lethalB)
    }

    @Test
    fun fighterRammingABomberDoesNotDestroyIt() {
        // F-16 (12.5 t) into an Il-76 (169 t), head-on at a combined 300 m/s.
        val fighter = Body(12.46, east.scale(9.0), true)
        val bomber = Body(169.2, east.scale(-6.0), true)
        val outcome = headOn(fighter, bomber)
        assertTrue(outcome.lethalA)
        assertFalse(outcome.lethalB)
        assertTrue(outcome.damageB <= VehicleImpactModel.heavyCap(169.2, 12.46) + 1e-9)
        assertTrue(outcome.damageB < 0.5, "bomber damage ${outcome.damageB}")
        // Nor from behind, and it keeps its wings.
        val wing = VehicleImpactModel.resolve(Body(12.46, east.scale(7.0), true),
            Body(169.2, east.scale(3.0), true, setOf(Zone.WING)), Vec3(-1.0, 0.0, 0.0))
        assertFalse(wing.shearB)
        assertFalse(wing.lethalB)
    }

    @Test
    fun heavyCapOnlyProtectsMuchHeavierVehicles() {
        assertEquals(Double.POSITIVE_INFINITY, VehicleImpactModel.heavyCap(25.8, 19.7))
        assertEquals(VehicleImpactModel.HEAVY_CAP_MIN, VehicleImpactModel.heavyCap(169.2, 7.35), 1e-12)
        assertTrue(VehicleImpactModel.heavyCap(169.2, 50.0) in VehicleImpactModel.HEAVY_CAP_MIN..1.0)
    }

    @Test
    fun wingClipTearsTheWingOffWithoutKillingTheHull() {
        // Two fighters, one clips the other's wing at 40 m/s closing.
        val outcome = VehicleImpactModel.resolve(Body(12.5, east.scale(2.0), true, setOf(Zone.WING)),
            Body(12.5, Vec3.ZERO, true, setOf(Zone.WING)), Vec3(-1.0, 0.0, 0.0))
        assertTrue(outcome.shearA && outcome.shearB)
        assertFalse(outcome.lethalA || outcome.lethalB)
        // A slow wingtip brush only scrapes.
        val brush = VehicleImpactModel.resolve(Body(12.5, east.scale(0.5), true, setOf(Zone.WING)),
            Body(12.5, Vec3.ZERO, true, setOf(Zone.WING)), Vec3(-1.0, 0.0, 0.0))
        assertFalse(brush.shearA || brush.shearB)
    }

    @Test
    fun formationTouchAndTaxiBumpAreHarmless() {
        val formation = headOn(Body(12.5, east.scale(10.0), true), Body(12.5, east.scale(9.95), true))
        assertFalse(formation.damages)
        val taxi = headOn(Body(25.8, east.scale(0.25), true, setOf(Zone.GEAR)), Body(44.5, Vec3.ZERO, false))
        assertFalse(taxi.lethalA || taxi.lethalB)
        assertTrue(taxi.damageA < 0.05)
    }

    @Test
    fun gearContactIsNeverLethal() {
        val outcome = headOn(Body(25.8, east.scale(4.0), true, setOf(Zone.GEAR)), Body(44.5, Vec3.ZERO, false))
        assertFalse(outcome.lethalA)
        assertTrue(outcome.damageA <= VehicleImpactModel.GEAR_CAP + 1e-12)
    }

    @Test
    fun glancingContactOnlyCountsTheNormalComponent() {
        val velocity = Vec3(1.0, 0.0, 0.1) // mostly along the contact surface
        val glancing = VehicleImpactModel.resolve(Body(25.8, velocity, true), Body(25.8, Vec3.ZERO, true),
            Vec3(0.0, 0.0, -1.0))
        assertEquals(0.1, glancing.closingSpeed, 1e-12)
        assertFalse(glancing.damages)
        assertTrue(abs(glancing.deltaA.x) < 1e-12)
    }

    @Test
    fun jetIntoTankIsDestroyedAndBadlyDamagesIt() {
        val outcome = headOn(Body(25.8, east.scale(3.0), true), Body(44.5, Vec3.ZERO, false))
        assertTrue(outcome.lethalA)
        assertTrue(outcome.damageB > 0.5, "tank damage ${outcome.damageB}")
    }
}

class VehicleImpactContactTest {
    private fun part(x: Double, zone: Zone = Zone.BODY) = VehicleEntityContacts.Part(
        com.atsuishio.superbwarfare.tools.OBB(org.joml.Vector3d(x, 0.0, 0.0), org.joml.Vector3d(1.0, 1.0, 1.0),
            org.joml.Quaterniond(), com.atsuishio.superbwarfare.tools.OBB.Part.BODY), zone == Zone.BODY, zone)

    @Test
    fun sweptContactNormalPointsFromTheOtherVehicleTowardThisOne() {
        val impact = VehicleEntityContacts.impact(listOf(part(0.0)), listOf(part(2.5, Zone.WING)), Vec3(1.0, 0.0, 0.0))!!
        assertEquals(0.5, impact.fraction, 1e-9)
        assertEquals(-1.0, impact.normal.x, 1e-9)
        assertEquals(0.0, impact.depth)
        assertEquals(setOf(Zone.WING), impact.otherZones)
    }

    @Test
    fun overlapReportsDepthAndOutwardNormal() {
        val impact = VehicleEntityContacts.impact(listOf(part(0.0)), listOf(part(1.5)), Vec3.ZERO)!!
        assertEquals(0.5, impact.depth, 1e-9)
        assertEquals(-1.0, impact.normal.x, 1e-9)
    }

    @Test
    fun missesAndSeparationAreNull() {
        assertEquals(null, VehicleEntityContacts.impact(listOf(part(0.0)), listOf(part(5.0)), Vec3(1.0, 0.0, 0.0)))
        assertEquals(null, VehicleEntityContacts.impact(listOf(part(0.0)), listOf(part(2.0)), Vec3(-1.0, 0.0, 0.0)))
    }
}
