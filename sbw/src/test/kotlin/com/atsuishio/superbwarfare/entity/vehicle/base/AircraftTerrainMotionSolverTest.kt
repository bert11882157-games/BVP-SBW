package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSurface
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingTerrainPrisms
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainBox
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftWheelContact
import com.atsuishio.superbwarfare.tools.OBB
import kotlinx.serialization.json.Json
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftTerrainMotionSolverTest {
    @Test fun authoredMig19sDivingGearCannotShieldFuselageAndLevelGearTakesTheCrashItself() {
        for (pitch in listOf(0.0, 60.0)) {
            val rotation = Quaterniond().rotateX(Math.toRadians(pitch))
            fun box(min: Vec3, max: Vec3): OBB {
                val center = min.add(max).scale(0.5)
                val transformed = rotation.transform(Vector3d(center.x, center.y, center.z)).add(0.0, 4.0, 0.0)
                val size = max.subtract(min).scale(0.5)
                return OBB(transformed, Vector3d(size.x, size.y, size.z), rotation, OBB.Part.BODY)
            }
            val boxes = listOf(
                false to box(Vec3(-2.0, 0.75, -2.8), Vec3(2.0, 2.65, 2.2)),
                true to box(Vec3(-1.77, 0.0, -2.4), Vec3(1.77, 0.8, 1.8)))
            val incoming = Vec3(0.0, -5.0, 2.0)
            val result = AircraftTerrainMotionSolver.resolve(incoming, incoming) { movement, offset ->
                val hits = boxes.mapNotNull { (gear, box) ->
                    val body = FixedWingContactSweep.Body(box.move(offset))
                    val raw = body.sweep(movement, floor) ?: return@mapNotNull null
                    val surface = FixedWingContactSurface.resolve(body, movement, floor, raw,
                        listOf(floor), FixedWingContactSurface.Budget(), allowGearSupportPoint = gear)
                    val normal = surface.normal ?: return@mapNotNull null
                    if (gear && !AircraftTerrainMotionSolver.acceptsGearContact(normal, box)) return@mapNotNull null
                    if (raw.penetrationDepth <= 1e-7 && movement.dot(normal) >= -1e-9) return@mapNotNull null
                    gear to raw.copy(normal = normal)
                }.sortedWith(compareBy<Pair<Boolean, FixedWingContactSweep.Contact>> { it.second.fraction }.thenBy { it.first })
                val hit = hits.firstOrNull()
                AircraftTerrainMotionSolver.Query(hit?.second, hit?.first == true, true)
            }
            if (pitch == 0.0) {
                assertTrue(result.gearContact && !result.bodyContact)
                assertEquals(5.0, result.gearImpactSpeedBlocksPerTick, 1e-8)
                assertTrue(result.damage.destructive, "a 100 m/s sink collapses deployed gear")
            } else {
                assertTrue(result.bodyContact && !result.gearGroundContact)
                assertTrue(result.damage.destructive)
            }
        }
    }

    @Test fun tiltedGearPointContactStaysSupportedThroughRepeatedGravityAndTaxiSteps() {
        for (bank in listOf(-0.7346883, 0.7346883)) {
            val box = OBB(Vector3d(0.0, 0.65, 0.0), Vector3d(2.4, 0.65, 3.35),
                Quaterniond().rotateX(Math.toRadians(-0.111996435)).rotateZ(Math.toRadians(bank)), OBB.Part.BODY)
            var position = Vec3(0.0, -FixedWingContactSweep.Body(box).bounds.minY, 0.0)
            val initialY = position.y
            repeat(600) {
                val velocity = Vec3(0.008, -0.024516625, 0.007)
                val result = AircraftTerrainMotionSolver.resolve(velocity, velocity) { remaining, admitted ->
                    val body = FixedWingContactSweep.Body(box.move(position.add(admitted)))
                    val raw = body.sweep(remaining, floor)
                    val surface = raw?.let { FixedWingContactSurface.resolve(body, remaining, floor, it,
                        listOf(floor), FixedWingContactSurface.Budget(), allowGearSupportPoint = true) }
                    val contact = surface?.normal?.let { raw!!.copy(normal = it) }?.takeUnless {
                        it.penetrationDepth <= 1e-7 && remaining.dot(it.normal) >= -1e-9
                    }
                    AircraftTerrainMotionSolver.Query(contact, true, surface?.complete != false)
                }
                assertTrue(result.below, "tilted gear lost support at step=$it")
                assertEquals(0.0, result.velocity.y, 1e-10)
                position = position.add(result.movement)
                assertEquals(initialY, position.y, 2e-6, "support reference oscillated")
                assertEquals(0.0, result.damage.healthFraction)
            }
        }
    }

    @Test fun terrainSchemaRoundTripsIndependentMinMaxVectors() {
        val text = """{"Fuselage":{"Min":[-1,1,-2],"Max":[1,3,2]},"LandingGear":{"Min":[-0.8,0,-1.5],"Max":[0.8,0.5,1.5]},"RetractableGear":true}"""
        val definition = Json.decodeFromString(AircraftTerrainContact.serializer(), text)
        assertTrue(definition.valid())
        val restored = Json.decodeFromString(AircraftTerrainContact.serializer(),
            Json.encodeToString(AircraftTerrainContact.serializer(), definition))
        assertEquals(definition.fuselage.minimum, restored.fuselage.minimum)
        assertEquals(definition.landingGear.maximum, restored.landingGear.maximum)
        assertTrue(restored.retractableGear)
        restored.landingGear.maximum = restored.landingGear.minimum
        assertFalse(restored.valid())
    }
    private val floor = AABB(-100.0, -1.0, -100.0, 100.0, 0.0, 100.0)
    private fun simulate(gear: Boolean, velocity: Vec3, obstacles: List<AABB> = listOf(floor),
                         origin: Vec3 = Vec3.ZERO): AircraftTerrainMotionSolver.Result {
        val boxes = listOf(false to OBB(Vector3d(0.0, 2.0, 0.0), Vector3d(1.0, 0.5, 2.0), Quaterniond(), OBB.Part.BODY)) +
            if (gear) listOf(true to OBB(Vector3d(0.0, 1.0, 0.0), Vector3d(0.8, 0.5, 1.5), Quaterniond(), OBB.Part.BODY)) else emptyList()
        return AircraftTerrainMotionSolver.resolve(velocity, velocity) { remaining, offset ->
            val hits = boxes.flatMap { (isGear, box) ->
                val body = FixedWingContactSweep.Body(box.move(offset.add(origin)))
                obstacles.mapNotNull { obstacle -> body.sweep(remaining, obstacle)?.takeUnless {
                    it.penetrationDepth <= 1e-7 && remaining.dot(it.normal) >= -1e-9
                }?.let { isGear to it } }
            }.sortedWith(compareBy<Pair<Boolean, FixedWingContactSweep.Contact>> { it.second.fraction }.thenBy { it.first })
            val first = hits.firstOrNull()
            AircraftTerrainMotionSolver.Query(first?.second, first?.first == true, true)
        }
    }

    @Test fun deployedGearSupportsTouchdownAndGradesDamageBySinkRate() {
        val severe = simulate(true, Vec3(0.5, -4.0, 0.0))
        assertTrue(severe.gearContact && severe.below && !severe.bodyContact)
        assertTrue(severe.gearGroundContact)
        assertEquals(4.0, severe.gearImpactSpeedBlocksPerTick, 1e-8)
        assertEquals(-0.5, severe.movement.y, 1e-8)
        assertEquals(0.5, severe.movement.x, 1e-8)
        assertEquals(0.0, severe.velocity.y)
        assertTrue(severe.damage.destructive, "an 80 m/s sink is a crash, not a landing")
        // Sink rates in blocks per tick: 1.5, 2.5, 4, 7 and 10 m/s.
        for ((sink, expected) in listOf(0.075 to "none", 0.125 to "none", 0.2 to "moderate",
            0.35 to "heavy", 0.5 to "lethal")) {
            val r = simulate(true, Vec3(0.5, -sink, 0.0), origin = Vec3(0.0, -0.5 + sink * 0.5, 0.0))
            assertTrue(r.gearGroundContact && !r.bodyContact, "sink=$sink $r")
            assertEquals(sink, r.gearImpactSpeedBlocksPerTick, 1e-8)
            assertEquals(0.5, r.velocity.x, 1e-8)
            when (expected) {
                "none" -> assertEquals(0.0, r.damage.healthFraction, 0.0, "sink=$sink")
                "moderate" -> assertTrue(r.damage.healthFraction in 0.02..0.15 && !r.damage.destructive, "$r")
                "heavy" -> assertTrue(r.damage.healthFraction in 0.2..0.6 && !r.damage.destructive, "$r")
                else -> assertTrue(r.damage.destructive, "$r")
            }
        }
    }

    @Test fun retractedGearFallsOntoFuselageAndSeverityControlsDestruction() {
        val severe = simulate(false, Vec3(0.0, -4.0, 0.0))
        assertTrue(severe.bodyContact && !severe.gearContact && severe.damage.destructive)
        assertFalse(severe.gearGroundContact)
        assertEquals(0.0, severe.gearImpactSpeedBlocksPerTick)
        assertEquals(-1.5, severe.movement.y, 1e-8)
        // A side scrape reaches the wall slowly without requiring a large fall.
        val wall = AABB(1.01, -1.0, -100.0, 2.0, 10.0, 100.0)
        val gentle = simulate(false, Vec3(0.05, 0.0, 0.0), listOf(wall))
        assertTrue(gentle.bodyContact && !gentle.damage.destructive)
        assertTrue(gentle.damage.healthFraction > 0.0)
    }

    @Test fun gearCannotHideAnIndependentBodyWallImpact() {
        val wall = AABB(1.1, 1.6, -100.0, 2.0, 10.0, 100.0)
        val r = simulate(true, Vec3(3.0, -4.0, 0.0), listOf(floor, wall))
        assertTrue(r.bodyContact && r.damage.destructive)
        assertTrue(r.movement.x <= 0.1 + 1e-7)
    }

    @Test fun supportEndpointAndUnknownWorldAreExplicit() {
        val definition = AircraftTerrainContact()
        assertTrue(definition.gearDeployed(Float.NaN))
        definition.retractableGear = true
        for (fraction in listOf(0.3F, 0.5F, 1F, Float.NaN, -1F)) assertFalse(definition.gearDeployed(fraction))
        // Mostly extended gear already carries the aircraft, so a late extension still lands.
        for (fraction in listOf(0F, 0.01F, 0.25F)) assertTrue(definition.gearDeployed(fraction))
        val r = AircraftTerrainMotionSolver.resolve(Vec3(2.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)) { _, _ ->
            AircraftTerrainMotionSolver.Query(null, false, false)
        }
        assertFalse(r.complete)
        assertEquals(Vec3.ZERO, r.movement)
        assertEquals(Vec3.ZERO, r.velocity)
        assertEquals(0.0, r.damage.healthFraction)
    }

    /** Separate tyre boxes and a high fuselage, resolved through the production contact admission. */
    private class WheelScene(terrain: List<AABB>, private val origin: Vec3) {
        private val terrain = FixedWingTerrainPrisms.compact(terrain)
        private fun box(center: Vec3, half: Vec3) = OBB(Vector3d(center.x + origin.x, center.y + origin.y,
            center.z + origin.z), Vector3d(half.x, half.y, half.z), Quaterniond(), OBB.Part.BODY)
        // Main tyres first: an embedded main straddling a block edge used to select that edge.
        private val boxes = listOf(
            true to box(Vec3(-1.5, 0.3, -0.5), Vec3(0.2, 0.3, 0.3)),
            true to box(Vec3(1.5, 0.3, -0.5), Vec3(0.2, 0.3, 0.3)),
            true to box(Vec3(0.0, 0.3, 2.0), Vec3(0.15, 0.3, 0.3)),
            false to box(Vec3(0.0, 2.0, 0.0), Vec3(1.0, 0.5, 3.0)))

        fun query(movement: Vec3, offset: Vec3): AircraftTerrainMotionSolver.Query {
            val budget = FixedWingContactSurface.Budget()
            var best: FixedWingContactSweep.Contact? = null
            var bestGear = false
            var complete = true
            for ((isGear, box) in boxes) {
                val body = FixedWingContactSweep.Body(box.move(offset))
                for (obstacle in terrain) {
                    val raw = body.sweep(movement, obstacle) ?: continue
                    val sample = AircraftTerrainContactQuery.resolve(body, box, isGear, movement,
                        obstacle, raw, terrain, budget)
                    complete = complete && sample.complete
                    val contact = sample.contact ?: continue
                    if (AircraftTerrainContactQuery.preferred(contact, isGear, best, bestGear)) {
                        best = contact
                        bestGear = isGear
                    }
                }
            }
            return AircraftTerrainMotionSolver.Query(best, bestGear, complete)
        }

        fun move(velocity: Vec3, supported: Boolean = true) =
            AircraftTerrainMotionSolver.resolve(velocity, velocity, supported, Vec3.ZERO, ::query)
    }
    private val runway = AABB(-100.0, -2.0, -100.0, 100.0, 0.0, 100.0)

    @Test fun wheelsRollOverNarrowBumpsAndSeamsWithoutLosingSpeedOrTakingDamage() {
        val bumps = mapOf(
            "slab row" to AABB(-50.0, 0.0, 6.0, 50.0, 0.5, 7.0),
            "carpet or path seam" to AABB(-50.0, 0.0, 6.0, 50.0, 0.0625, 6.5),
            "snow layer" to AABB(-50.0, 0.0, 6.0, 50.0, 0.125, 8.0),
            "block row" to AABB(-50.0, 0.0, 6.0, 50.0, 1.0, 7.0))
        for ((name, bump) in bumps) for (speed in listOf(0.2, 0.5, 1.5)) {
            var position = Vec3.ZERO
            var stepped = false
            repeat((16.0 / speed).toInt()) { tick ->
                val result = WheelScene(listOf(runway, bump), position).move(Vec3(0.0, -0.012, speed))
                val label = "$name speed=$speed tick=$tick $result"
                assertTrue(result.complete && result.gearGroundContact && !result.bodyContact, label)
                assertEquals(speed, result.movement.z, 1e-7, label)
                assertEquals(speed, result.velocity.z, 1e-7, label)
                assertEquals(0.0, result.damage.healthFraction, label)
                stepped = stepped || result.gearStepUsed
                position = position.add(result.movement)
            }
            assertTrue(stepped, "$name speed=$speed")
            assertEquals(0.0, position.y, 1e-6, "$name speed=$speed")
        }
    }

    @Test fun rotationEmbeddedWheelsAreLiftedOntoTheirBlockInsteadOfStopped() {
        val plateau = AABB(-50.0, 0.0, 5.0, 50.0, 0.5, 100.0)
        // Main tyres straddle the plateau edge; every tyre sits 0.06 below its top after a pitch change.
        val origin = Vec3(0.0, 0.44, 5.3)
        for (speed in listOf(0.1, 0.5, 2.0)) {
            val result = WheelScene(listOf(runway, plateau), origin).move(Vec3(0.0, -0.012, speed))
            assertTrue(result.gearGroundContact && !result.bodyContact, result.toString())
            assertEquals(speed, result.movement.z, 1e-7, result.toString())
            assertEquals(speed, result.velocity.z, 1e-7)
            assertEquals(0.5, origin.y + result.movement.y, 1e-6)
            assertEquals(0.0, result.damage.healthFraction)
        }
    }

    @Test fun wheelMeetingAWallAboveStepHeightDeceleratesWithGradedDamageInsteadOfStoppingDead() {
        // Taller than a step but below the fuselage: only the nose tyre can strike it.
        val wall = AABB(-50.0, 0.0, 6.0, 50.0, 1.2, 7.0)
        val origin = Vec3(0.0, 0.0, 3.5)
        val taxi = WheelScene(listOf(runway, wall), origin).move(Vec3(0.0, -0.012, 1.0))
        assertFalse(taxi.gearStepUsed || taxi.bodyContact, taxi.toString())
        assertTrue(taxi.gearContact && taxi.horizontal)
        assertTrue(taxi.movement.z < 1.0)
        assertEquals(0.5, taxi.velocity.z, 1e-7, "a tyre strike sheds speed over several ticks")
        assertTrue(taxi.damage.healthFraction > 0.1 && taxi.damage.healthFraction < 0.5, taxi.toString())
        assertFalse(taxi.damage.destructive)
        val creeping = WheelScene(listOf(runway, wall), Vec3(0.0, 0.0, 3.65)).move(Vec3(0.0, -0.012, 0.1))
        assertTrue(creeping.gearContact && creeping.horizontal && !creeping.bodyContact)
        assertEquals(0.0, creeping.velocity.z, 1e-7)
        assertEquals(0.0, creeping.damage.healthFraction)
        val extreme = WheelScene(listOf(runway, wall), origin).move(Vec3(0.0, -0.012, 2.0))
        assertTrue(extreme.damage.destructive)
    }

    @Test fun groundPitchLimitKeepsTheTailClearOfTheRunway() {
        fun aircraft(tailMinY: Double, tailMinZ: Double) = AircraftTerrainContact().apply {
            fun box(min: Vec3, max: Vec3) = AircraftTerrainBox().apply { minimum = min; maximum = max }
            fuselage = box(Vec3(-1.0, 1.0, -2.0), Vec3(1.0, 2.5, 6.0))
            landingGear = box(Vec3(-2.0, 0.0, -0.4), Vec3(2.0, 0.6, 4.4))
            bodyParts = listOf(fuselage, box(Vec3(-0.5, tailMinY, tailMinZ), Vec3(0.5, tailMinY + 1.0, -0.5)))
            retractableGear = true
            wheelContacts = listOf(Vec3(-2.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), Vec3(0.0, 0.0, 4.0))
                .mapIndexed { index, point -> AircraftWheelContact().apply {
                    id = "wheel_$index"
                    group = if (index == 2) AircraftWheelContactGroup.NOSE else AircraftWheelContactGroup.MAIN
                    position = point
                    bounds = box(point.add(-0.2, 0.0, -0.4), point.add(0.2, 0.4, 0.4))
                } }
        }
        val typical = aircraft(1.0, -4.6)
        assertTrue(typical.valid())
        val clearance = Math.toDegrees(kotlin.math.atan2(1.0, 4.2))
        assertEquals(clearance, typical.tailClearanceDegrees()!!, 1e-9)
        assertEquals(clearance - 1.5, typical.groundPitchLimitDegrees(), 1e-9)
        // Low stores leave little clearance, but a takeoff attitude always remains.
        assertEquals(4.0, aircraft(0.15, -2.0).groundPitchLimitDegrees(), 1e-9)
        assertEquals(18.0, aircraft(3.0, -2.5).groundPitchLimitDegrees(), 1e-9)
        assertEquals(18.0, AircraftTerrainContact().groundPitchLimitDegrees())
        assertNull(AircraftTerrainContact().tailClearanceDegrees())
    }
}
