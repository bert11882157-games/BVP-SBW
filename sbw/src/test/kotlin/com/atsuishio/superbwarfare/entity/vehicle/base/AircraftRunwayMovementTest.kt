package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSurface
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingTerrainPrisms
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.tools.OBB
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftRunwayMovementTest {
    private val floor = AABB(-100.0, -2.0, -100.0, 100.0, 0.0, 100.0)
    private data class Fit(val id: String, val contact: AircraftTerrainContact, val pivot: Double)
    private companion object {
        val fits = Json.parseToJsonElement(AircraftRunwayMovementTest::class.java
            .getResourceAsStream("/aircraft_runway_fits.json")!!.bufferedReader().use { it.readText() })
            .jsonObject.map { (id, value) ->
                Fit(id, Json.decodeFromString(AircraftTerrainContact.serializer(),
                    value.jsonObject.getValue("contact").toString()),
                    value.jsonObject.getValue("rotateOffsetHeight").jsonPrimitive.double)
            }
        val mig = fits.first { it.id == "mig_19s" }
        val mig15 = Json.parseToJsonElement(AircraftRunwayMovementTest::class.java
            .getResourceAsStream("/aircraft_entity_collision_fits.json")!!.bufferedReader().use { it.readText() })
            .jsonObject.getValue("mig_15bis").jsonObject.let {
                Fit("mig_15bis", Json.decodeFromString(AircraftTerrainContact.serializer(),
                    it.getValue("contact").toString()), it.getValue("rotateOffsetHeight").jsonPrimitive.double)
            }
    }

    /** Uses the actual world-probe surface admission, terrain compaction and movement transaction. */
    private class Scene(val terrain: List<AABB>, val origin: Vec3 = Vec3.ZERO,
                        val rotation: Quaterniond = Quaterniond(), val gear: Boolean = true,
                        val fit: Fit = mig) {
        private fun box(min: Vec3, max: Vec3): OBB {
            val center = min.add(max).scale(0.5)
            val transformed = rotation.transform(Vector3d(center.x, center.y - fit.pivot, center.z))
                .add(origin.x, origin.y + fit.pivot, origin.z)
            val half = max.subtract(min).scale(0.5)
            return OBB(transformed, Vector3d(half.x, half.y, half.z), rotation, OBB.Part.BODY)
        }

        private val boxes = listOf(false to box(fit.contact.fuselage.minimum, fit.contact.fuselage.maximum)) +
            if (gear) listOf(true to box(fit.contact.landingGear.minimum, fit.contact.landingGear.maximum)) else emptyList()
        private val compactTerrain = FixedWingTerrainPrisms.compact(terrain)

        fun query(movement: Vec3, offset: Vec3): AircraftTerrainMotionSolver.Query {
            val budget = FixedWingContactSurface.Budget()
            var best: FixedWingContactSweep.Contact? = null
            var bestGear = false
            var complete = true
            for ((isGear, box) in boxes) {
                val body = FixedWingContactSweep.Body(box.move(offset))
                for (obstacle in compactTerrain) {
                    val raw = body.sweep(movement, obstacle) ?: continue
                    val sample = AircraftTerrainContactQuery.resolve(body, box, isGear, movement,
                        obstacle, raw, compactTerrain, budget)
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

        fun move(velocity: Vec3, supported: Boolean = true, direction: Vec3 = velocity): AircraftTerrainMotionSolver.Result {
            val reach = if (gear) AircraftTerrainMotionSolver.gearSupportReach(boxes[0].second,
                boxes[1].second, direction) else Vec3.ZERO
            return AircraftTerrainMotionSolver.resolve(velocity, velocity, supported, reach, ::query)
        }
    }

    @Test fun halfAndFullBlockStepsPreserveRunwayMomentumAcrossSpeeds() {
        for (fit in fits) for (height in listOf(0.5, 1.0)) for (speed in listOf(0.1, 0.5, 1.5, 5.0)) {
            val step = AABB(-50.0, 0.0, fit.contact.fuselage.maximum.z + 0.1, 50.0, height, 100.0)
            var position = Vec3.ZERO
            var stepped = false
            repeat(kotlin.math.ceil(5.0 / speed).toInt()) {
                val result = Scene(listOf(floor, step), origin = position, fit = fit).move(Vec3(0.0, -0.012, speed))
                stepped = stepped || result.gearStepUsed
                assertTrue(result.gearGroundContact && !result.bodyContact, "${fit.id} step=$height speed=$speed tick=$it")
                assertEquals(speed, result.movement.z, 1e-7, "step=$height speed=$speed tick=$it")
                assertEquals(speed, result.velocity.z, 1e-7)
                assertEquals(0.0, result.damage.healthFraction)
                position = position.add(result.movement)
            }
            assertTrue(stepped, "step=$height speed=$speed")
            assertEquals(height, position.y, 1e-6)
        }
    }

    @Test fun obliqueStepKeepsBothHorizontalVelocityComponents() {
        val step = AABB(-50.0, 0.0, 4.0, 50.0, 1.0, 100.0)
        val result = Scene(listOf(floor, step), rotation = Quaterniond().rotateY(0.2))
            .move(Vec3(0.4, -0.012, 1.4))
        assertTrue(result.gearStepUsed)
        assertEquals(0.4, result.velocity.x, 1e-7)
        assertEquals(1.4, result.velocity.z, 1e-7)
        assertEquals(0.4, result.movement.x, 1e-7)
        assertEquals(1.4, result.movement.z, 1e-7)
    }

    @Test fun minorDropsFollowGearWithoutSyntheticImpactButCliffsFall() {
        for (depth in listOf(0.5, 1.0, 1.25)) {
            val platform = AABB(-50.0, -2.0, -100.0, 50.0, 0.0, -2.5)
            val lower = AABB(-50.0, -3.0, -2.5, 50.0, -depth, 100.0)
            val result = Scene(listOf(platform, lower)).move(Vec3(0.0, -0.012, 1.0))
            assertEquals(1.0, result.velocity.z, 1e-7)
            assertEquals(1.0, result.movement.z, 1e-7)
            if (depth <= 1.0) {
                assertTrue(result.gearGroundContact)
                assertEquals(-depth, result.movement.y, 1e-6)
                assertEquals(0.0, result.gearImpactSpeedBlocksPerTick)
            } else {
                assertFalse(result.gearGroundContact)
                val falling = Scene(listOf(platform, lower), origin = result.movement)
                    .move(Vec3(0.0, -0.012, 1.0), false)
                assertEquals(-0.012, falling.movement.y, 1e-7)
                assertEquals(-0.012, falling.velocity.y, 1e-7)
            }
        }
    }

    @Test fun airborneAscendingRetractedAndSteepAttitudeNeverGetRunwayClimb() {
        val step = AABB(-50.0, 0.0, mig.contact.fuselage.maximum.z + 0.1, 50.0, 1.0, 100.0)
        val scene = Scene(listOf(floor, step))
        assertFalse(scene.move(Vec3(0.0, -0.012, 1.0), false).gearStepUsed)
        assertFalse(scene.move(Vec3(0.0, 0.05, 1.0)).gearStepUsed)
        assertFalse(Scene(listOf(floor, step), gear = false).move(Vec3(0.0, -0.012, 1.0)).gearStepUsed)
        val tilted = Scene(listOf(floor, step), origin = Vec3(0.0, 4.0, 0.0),
            rotation = Quaterniond().rotateX(Math.toRadians(60.0)))
        assertTrue(tilted.move(Vec3(0.0, -5.0, 1.0), false).damage.destructive)
    }

    @Test fun solidWallRetainsSpeedDependentFuselageImpactAndCannotBeStepped() {
        for (fit in fits) for (speed in listOf(0.1, 0.5, 1.5, 5.0)) {
            val wall = AABB(-50.0, 0.0, fit.contact.fuselage.maximum.z + speed * 0.25, 50.0, 20.0, 100.0)
            val result = Scene(listOf(floor, wall), fit = fit).move(Vec3(0.0, -0.012, speed))
            assertFalse(result.gearStepUsed)
            assertTrue(result.bodyContact)
            assertEquals(speed >= 0.3, result.damage.destructive)
            assertTrue(result.movement.z < speed)
            assertEquals(0.0, result.velocity.z, 1e-7)
        }
    }

    @Test fun mig15RunFromRestHitsHouseWithoutAnAirborneState() {
        for (wallWidth in listOf(0.125, 1.0)) for (yaw in listOf(0.0, 0.2)) {
            val wall = AABB(-50.0, 0.0, 20.0, 50.0, 5.0, 20.0 + wallWidth)
            var position = Vec3.ZERO
            var supported = false
            var speed = 0.0
            var hit = false
            for (tick in 0 until 160) {
                speed += 0.006
                val velocity = Vec3(speed * kotlin.math.sin(yaw), -0.012, speed * kotlin.math.cos(yaw))
                val result = Scene(listOf(floor, wall), position, Quaterniond().rotateY(yaw), fit = mig15)
                    .move(velocity, supported)
                assertTrue(result.complete, "width=$wallWidth yaw=$yaw tick=$tick $result")
                assertTrue(result.gearGroundContact, "left runway before contact: $result")
                if (result.bodyContact) {
                    assertFalse(result.gearStepUsed)
                    assertTrue(result.damage.destructive, "width=$wallWidth yaw=$yaw $result")
                    hit = true
                    break
                }
                supported = result.gearGroundContact
                position = position.add(result.movement)
            }
            assertTrue(hit, "width=$wallWidth yaw=$yaw pos=$position speed=$speed")
        }
    }

    @Test fun mig15GroundAndAirWallDamageUsesTheSameNormalSpeed() {
        for (speed in listOf(0.01, 0.1, 0.299, 0.3, 0.5)) {
            val wall = AABB(-50.0, 0.0, mig15.contact.fuselage.maximum.z + speed * 0.25,
                50.0, 20.0, 30.0)
            val velocity = Vec3(0.0, -0.012, speed)
            val ground = Scene(listOf(floor, wall), fit = mig15).move(velocity, true)
            val air = Scene(listOf(floor, wall), origin = Vec3(0.0, 5.0, 0.0), fit = mig15)
                .move(velocity, false)
            assertTrue(ground.bodyContact && air.bodyContact, "speed=$speed ground=$ground air=$air")
            assertEquals(air.damage, ground.damage, "speed=$speed")
            assertEquals(speed >= 0.3, ground.damage.destructive)
            if (speed > 0.01) assertTrue(ground.damage.healthFraction > 0.0)
            assertFalse(ground.gearStepUsed)
        }
    }

    @Test fun mig15HouseWallBehindLowDoorstepRetainsBodyImpact() {
        val doorstep = AABB(-50.0, 0.0, 2.6, 50.0, 0.5, 5.0)
        val wall = AABB(-50.0, 0.5, 3.6, 50.0, 5.0, 4.6)
        val result = Scene(listOf(floor, doorstep, wall), fit = mig15).move(Vec3(0.0, -0.012, 1.0))
        assertTrue(result.bodyContact && result.damage.destructive, result.toString())
    }

    @Test fun mig15RuntimeDoorstepKeepsMomentumWhenWallIsOnlyInSupportLookahead() {
        val doorstep = AABB(-50.0, 0.0, 2.6, 50.0, 0.5, 5.0)
        val wall = AABB(-50.0, 0.5, 3.6, 50.0, 5.0, 4.6)
        val origin = Vec3(0.0, 0.0, -0.22099290341483346)
        val velocity = Vec3(0.0, -0.005963206513956538, 0.9950632131765376)
        val step = Scene(listOf(floor, doorstep, wall), origin, fit = mig15).move(velocity)
        assertTrue(step.gearStepUsed && step.gearGroundContact, step.toString())
        assertFalse(step.bodyContact)
        assertEquals(velocity.z, step.velocity.z, 1e-8)
        assertEquals(velocity.z, step.movement.z, 1e-8)
        assertEquals(0.0, step.damage.healthFraction)
        val hit = Scene(listOf(floor, doorstep, wall), origin.add(step.movement), fit = mig15).move(velocity)
        assertTrue(hit.bodyContact && hit.damage.destructive, hit.toString())
    }

    @Test fun overhangAndMissingTerrainProofRejectSpeculativeStep() {
        val step = AABB(-50.0, 0.0, mig.contact.fuselage.maximum.z + 0.1, 50.0, 1.0, 100.0)
        val ceiling = AABB(-50.0, 2.7, -50.0, 50.0, 3.0, 100.0)
        val blocked = Scene(listOf(floor, step, ceiling)).move(Vec3(0.0, -0.012, 1.0))
        assertFalse(blocked.gearStepUsed)
        assertTrue(blocked.bodyContact)
        val scene = Scene(listOf(floor, step))
        val velocity = Vec3(0.0, -0.012, 1.0)
        val unknown = AircraftTerrainMotionSolver.resolve(velocity, velocity, true) { movement, offset ->
            if (movement.y > 0.0) AircraftTerrainMotionSolver.Query(null, false, false)
            else scene.query(movement, offset)
        }
        assertFalse(unknown.gearStepUsed)
        assertTrue(unknown.bodyContact)
    }

    @Test fun ledgesAboveOneBlockRetainTheirDirectBodyImpact() {
        for (height in listOf(1.01, 1.5)) {
            val ledge = AABB(-50.0, 0.0, mig.contact.fuselage.maximum.z + 0.1,
                50.0, height, 100.0)
            val result = Scene(listOf(floor, ledge)).move(Vec3(0.0, -0.012, 1.0))
            assertFalse(result.gearStepUsed)
            assertTrue(result.bodyContact && result.damage.destructive)
            assertEquals(0.0, result.velocity.z, 1e-7)
        }
    }

    @Test fun normalAndHardGearLandingsRemainNondamagingAndUseActualImpactSpeed() {
        for (fit in fits) for (speed in listOf(0.03, 0.2, 2.0)) {
            val result = Scene(listOf(floor), origin = Vec3(0.0, speed * 0.5, 0.0), fit = fit)
                .move(Vec3(0.0, -speed, 0.5), false)
            assertTrue(result.gearGroundContact && !result.bodyContact)
            assertEquals(0.0, result.damage.healthFraction)
            assertEquals(speed, result.gearImpactSpeedBlocksPerTick, 1e-7)
            assertEquals(0.5, result.velocity.z, 1e-7)
        }
    }

    @Test fun parkingAcrossInsetGearApproachRetainsSupportWithoutRepeatedShake() {
        val step = AABB(-50.0, 0.0, mig.contact.fuselage.maximum.z + 0.05, 50.0, 1.0, 100.0)
        val first = Scene(listOf(floor, step)).move(Vec3(0.0, -0.012, 0.1))
        assertTrue(first.gearStepUsed)
        var position = first.movement
        val gate = AircraftGearImpactGate()
        repeat(180) {
            val result = Scene(listOf(floor, step), origin = position)
                .move(Vec3(0.0, -0.012, 0.0), direction = Vec3(0.0, 0.0, 1.0))
            assertTrue(result.gearGroundContact && !result.bodyContact)
            assertEquals(0.0, result.movement.y, 1e-6)
            assertEquals(0.0, gate.sample(it.toLong(), result.complete,
                result.gearGroundContact, result.gearImpactSpeedBlocksPerTick))
            position = position.add(result.movement)
        }
    }

    @Test fun stairSlopeAndObliqueWallKeepAdmittedTangentialMotion() {
        val steps = (0..5).map { i -> AABB(-50.0, -2.0, 3.2 + i * 2,
            50.0, 0.25 * (i + 1), 5.2 + i * 2) }
        var position = Vec3.ZERO
        repeat(55) {
            val result = Scene(listOf(floor) + steps, origin = position).move(Vec3(0.03, -0.012, 0.2))
            assertTrue(result.gearGroundContact && !result.bodyContact)
            assertEquals(0.2, result.movement.z, 1e-7)
            assertEquals(0.03, result.velocity.x, 1e-7)
            assertEquals(0.0, result.damage.healthFraction)
            position = position.add(result.movement)
        }
        assertTrue(position.y > 1.0)
        val wall = AABB(2.52, 0.0, -100.0, 50.0, 10.0, 100.0)
        val scrape = Scene(listOf(floor, wall)).move(Vec3(0.03, -0.012, 1.5))
        assertTrue(scrape.bodyContact && !scrape.damage.destructive)
        assertEquals(1.5, scrape.velocity.z, 1e-7)
        assertEquals(1.5, scrape.movement.z, 1e-7)
    }

    @Test fun acceptedHelicopterFitsKeepTiltedTaxiSupportAndQuietImpactGate() {
        for (fit in fits.filter { it.id != "mig_19s" }) for (bank in listOf(-1.0, 1.0)) {
            val rotation = Quaterniond().rotateX(Math.toRadians(-0.2)).rotateZ(Math.toRadians(bank))
            val min = fit.contact.landingGear.minimum
            val max = fit.contact.landingGear.maximum
            var lowest = Double.POSITIVE_INFINITY
            for (x in listOf(min.x, max.x)) for (y in listOf(min.y, max.y)) for (z in listOf(min.z, max.z)) {
                lowest = kotlin.math.min(lowest, rotation.transform(Vector3d(x, y - fit.pivot, z)).y + fit.pivot)
            }
            var position = Vec3(0.0, -lowest, 0.0)
            val initialY = position.y
            val gate = AircraftGearImpactGate()
            repeat(180) {
                val result = Scene(listOf(floor), position, rotation, fit = fit)
                    .move(Vec3(0.007, -0.024516625, 0.008))
                assertTrue(result.gearGroundContact && !result.bodyContact, "${fit.id} bank=$bank tick=$it")
                assertEquals(0.0, result.velocity.y, 1e-7)
                assertEquals(0.007, result.velocity.x, 1e-7)
                assertEquals(0.008, result.velocity.z, 1e-7)
                assertEquals(0.0, gate.sample(it.toLong(), result.complete,
                    result.gearGroundContact, result.gearImpactSpeedBlocksPerTick))
                position = position.add(result.movement)
                assertEquals(initialY, position.y, 2e-6)
            }
        }
    }

    @Test fun tinyTiltedGearCornerAboveRunwayDoesNotClipForwardMotion() {
        val runway = AABB(2000.0, -60.0, 2038.0, 2091.0, -59.0, 2141.0)
        val raised = AABB(2000.0, -59.0, 2070.0, 2091.0, -58.0, 2141.0)
        val origin = Vec3(2042.7831306287355, -57.999998706329734, 2070.332282711874)
        val rotation = Quaterniond(-2.645354033168231e-7, -0.17197684806620814,
            -1.7352086374995038e-6, 0.9851009916379799)
        val velocity = Vec3(-0.31439907968703257, -0.005994057192745564, 0.8762055300020192)
        val blocks = buildList {
            for (x in 2035..2050) for (z in 2060..2085) {
                add(AABB(x.toDouble(), -60.0, z.toDouble(), x + 1.0, -59.0, z + 1.0))
                if (z >= 2070) add(AABB(x.toDouble(), -59.0, z.toDouble(), x + 1.0, -58.0, z + 1.0))
            }
        }
        for (terrain in listOf(listOf(runway, raised), blocks)) {
            val result = Scene(terrain, origin, rotation).move(velocity)
            assertTrue(result.gearGroundContact && !result.bodyContact)
            assertEquals(velocity.z, result.velocity.z, 1e-7, result.toString())
            assertEquals(velocity.z, result.movement.z, 1e-7)
            assertEquals(velocity.x, result.velocity.x, 1e-7)
            assertEquals(0.0, result.damage.healthFraction)
        }
    }

    @Test fun shallowGearSupportStillRejectsAnOccludedTopFace() {
        val box = OBB(Vector3d(0.0, 0.49999, 0.0), Vector3d(1.0, 0.5, 1.0), Quaterniond(), OBB.Part.BODY)
        val body = FixedWingContactSweep.Body(box)
        val lower = AABB(-5.0, -1.0, -5.0, 5.0, 0.0, 5.0)
        val cover = AABB(-2.0, 0.0, -2.0, 2.0, 1.0, 2.0)
        val motion = Vec3(0.0, -0.006, 0.8)
        val contact = body.sweep(motion, lower)!!
        val result = AircraftTerrainContactQuery.resolve(body, box, true, motion, lower, contact,
            listOf(lower, cover), FixedWingContactSurface.Budget())
        assertTrue(result.complete)
        assertNull(result.contact)
    }

    @Test fun tinyTiltedGearSupportDoesNotHideAnActualBodyWall() {
        val runway = AABB(2000.0, -60.0, 2038.0, 2091.0, -58.0, 2141.0)
        val wall = AABB(2000.0, -58.0, 2074.5, 2091.0, -50.0, 2076.0)
        val origin = Vec3(2042.7831306287355, -57.999998706329734, 2070.332282711874)
        val rotation = Quaterniond(-2.645354033168231e-7, -0.17197684806620814,
            -1.7352086374995038e-6, 0.9851009916379799)
        val velocity = Vec3(-0.31439907968703257, -0.005994057192745564, 0.8762055300020192)
        val result = Scene(listOf(runway, wall), origin, rotation).move(velocity)
        assertTrue(result.bodyContact && result.damage.destructive, result.toString())
        assertEquals(0.0, result.velocity.z, 1e-7)
    }
}
