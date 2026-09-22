package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSurface
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.tools.OBB
import kotlinx.serialization.json.Json
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftTerrainMotionSolverTest {
    @Test fun authoredMig19sDivingGearCannotShieldFuselageButLevelGearStillAbsorbsLanding() {
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
                assertEquals(0.0, result.damage.healthFraction)
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
    private fun simulate(gear: Boolean, velocity: Vec3, obstacles: List<AABB> = listOf(floor)):
        AircraftTerrainMotionSolver.Result {
        val boxes = listOf(false to OBB(Vector3d(0.0, 2.0, 0.0), Vector3d(1.0, 0.5, 2.0), Quaterniond(), OBB.Part.BODY)) +
            if (gear) listOf(true to OBB(Vector3d(0.0, 1.0, 0.0), Vector3d(0.8, 0.5, 1.5), Quaterniond(), OBB.Part.BODY)) else emptyList()
        return AircraftTerrainMotionSolver.resolve(velocity, velocity) { remaining, offset ->
            val hits = boxes.flatMap { (isGear, box) ->
                val body = FixedWingContactSweep.Body(box.move(offset))
                obstacles.mapNotNull { obstacle -> body.sweep(remaining, obstacle)?.takeUnless {
                    it.penetrationDepth <= 1e-7 && remaining.dot(it.normal) >= -1e-9
                }?.let { isGear to it } }
            }.sortedWith(compareBy<Pair<Boolean, FixedWingContactSweep.Contact>> { it.second.fraction }.thenBy { it.first })
            val first = hits.firstOrNull()
            AircraftTerrainMotionSolver.Query(first?.second, first?.first == true, true)
        }
    }

    @Test fun deployedGearSupportsEvenSevereTouchdownWithoutDamage() {
        val r = simulate(true, Vec3(0.5, -4.0, 0.0))
        assertTrue(r.gearContact && r.below && !r.bodyContact)
        assertTrue(r.gearGroundContact)
        assertEquals(4.0, r.gearImpactSpeedBlocksPerTick, 1e-8)
        assertEquals(-0.5, r.movement.y, 1e-8)
        assertEquals(0.5, r.movement.x, 1e-8)
        assertEquals(0.0, r.velocity.y)
        assertEquals(0.0, r.damage.healthFraction)
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
        for (fraction in listOf(0.01F, 0.5F, 1F, Float.NaN, -1F)) assertFalse(definition.gearDeployed(fraction))
        assertTrue(definition.gearDeployed(0F))
        val r = AircraftTerrainMotionSolver.resolve(Vec3(2.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)) { _, _ ->
            AircraftTerrainMotionSolver.Query(null, false, false)
        }
        assertFalse(r.complete)
        assertEquals(Vec3.ZERO, r.movement)
        assertEquals(Vec3.ZERO, r.velocity)
        assertEquals(0.0, r.damage.healthFraction)
    }
}
