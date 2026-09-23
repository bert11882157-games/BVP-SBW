package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.*
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftWheelContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainBox
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** No game/world is launched. Uses authored fleet points and the production support/movement kernels. */
class AircraftWheelSupportTest {
    private val main = AircraftWheelContactGroup.MAIN
    private val nose = AircraftWheelContactGroup.NOSE
    private val tail = AircraftWheelContactGroup.TAIL
    private val floor = AABB(-200.0, -10.0, -200.0, 200.0, 0.0, 200.0)
    private val fits = Json.parseToJsonElement(javaClass.getResourceAsStream("/aircraft_wheel_contact_fits.json")!!
        .bufferedReader().use { it.readText() }).jsonObject.mapValues {
        Json.decodeFromString(AircraftTerrainContact.serializer(), it.value.toString())
    }

    private class Scene(terrain: List<AABB>) {
        val terrain = FixedWingTerrainPrisms.compact(terrain)
        val kernel = AircraftWheelSupportKernel { boxes, movement, offset -> query(boxes, movement, offset).let {
            AircraftWheelSupportKernel.Probe(it.first.contact, it.first.complete, it.second)
        } }
        fun query(boxes: List<OBBInfo>, movement: Vec3, offset: Vec3): Pair<AircraftTerrainMotionSolver.Query, Boolean> {
            val budget = FixedWingContactSurface.Budget()
            var best: FixedWingContactSweep.Contact? = null
            var gear = false; var complete = true; var bodyOverlap = false
            for (info in boxes) {
                val box = info.getOBB()
                val body = FixedWingContactSweep.Body(box.move(offset))
                for (obstacle in terrain) {
                    val raw = body.sweep(movement, obstacle) ?: continue
                    if (!info.landingGear && raw.initiallyOverlapping) bodyOverlap = true
                    val result = AircraftTerrainContactQuery.resolve(body, box, info.landingGear, movement,
                        obstacle, raw, terrain, budget)
                    complete = complete && result.complete
                    val hit = result.contact ?: continue
                    if (AircraftTerrainContactQuery.preferred(hit, info.landingGear, best, gear)) {
                        best = hit; gear = info.landingGear
                    }
                }
            }
            return AircraftTerrainMotionSolver.Query(best, gear, complete) to bodyOverlap
        }
        fun move(data: AircraftTerrainContact, frame: Matrix4d, velocity: Vec3, supported: Boolean): AircraftTerrainMotionSolver.Result {
            val points = AircraftWheelGeometry.sample(data, frame)
            val boxes = AircraftCollisionSnapshot.create(data, frame, 0F).terrainInfos().filterNot { it.landingGear } + points.map { it.terrainInfo() }
            val envelope = AircraftWheelGeometry.envelope(points)
            val reach = AircraftTerrainMotionSolver.gearSupportReach(boxes.first().getOBB(), envelope, velocity)
            return AircraftTerrainMotionSolver.resolve(velocity, velocity, supported, reach) { movement, offset ->
                query(boxes, movement, offset).first
            }
        }
    }

    private fun solidWheelAircraft() = AircraftTerrainContact().apply {
        fun box(min: Vec3, max: Vec3) = AircraftTerrainBox().apply { minimum = min; maximum = max }
        fuselage = box(Vec3(-1.0, 1.3, -4.0), Vec3(1.0, 2.5, 4.0))
        landingGear = box(Vec3(-2.4, 0.0, -2.0), Vec3(2.4, 0.6, 3.0))
        bodyParts = listOf(fuselage,
            box(Vec3(-4.0, 1.4, -1.0), Vec3(-1.0, 1.7, 1.0)),
            box(Vec3(1.0, 1.4, -1.0), Vec3(4.0, 1.7, 1.0)))
        retractableGear = true
        wheelContacts = listOf(Vec3(-2.0, 0.0, -1.0), Vec3(2.0, 0.0, -1.0), Vec3(0.0, 0.0, 2.5))
            .mapIndexed { index, point -> AircraftWheelContact().apply {
                id = "wheel_$index"; group = if (index == 2) nose else main; position = point
                bounds = box(point.add(-0.25, 0.0, -0.4), point.add(0.25, 0.6, 0.4))
            } }
    }

    @Test fun partitionedBodiesKeepWingTipsAndGearGapsNonSolid() {
        val data = solidWheelAircraft()
        val snapshot = AircraftCollisionSnapshot.create(data, Matrix4d(), 0F)
        assertEquals(6, snapshot.parts.size)
        assertNull(snapshot.clip(Vec3(5.0, 3.0, 0.0), Vec3(5.0, 0.0, 0.0)), "outer third of wing is not crash geometry")
        assertNotNull(snapshot.clip(Vec3(3.0, 3.0, 0.0), Vec3(3.0, 0.0, 0.0)), "inner wing is solid")
        assertNull(snapshot.clip(Vec3(0.0, 0.2, -3.0), Vec3(0.0, 0.2, 1.0)), "no aggregate solid between main wheels")
        assertEquals(3, AircraftCollisionSnapshot.create(data, Matrix4d(), 1F).parts.count { it.active })
        val scene = Scene(listOf(AABB(2.9, 0.0, -0.2, 3.1, 1.0, 0.2)))
        assertTrue(scene.move(data, Matrix4d(), Vec3(0.0, -1.0, 0.0), false).bodyContact,
            "terrain movement must include wings, not just the first body")
    }

    @Test fun expansionSourceWheelSetsSettleFromLevelAndNoseFirstWithoutSuspendedMains() {
        val authored = Json.parseToJsonElement(javaClass.getResourceAsStream("/aircraft_expansion_wheel_contact_fits.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
        assertEquals(13, authored.size)
        val scene = Scene(listOf(floor))
        val spread: (String) -> Matrix4d = { Matrix4d() }
        for ((id, json) in authored) for (pitch in listOf(0.0,2.0)) {
            val data = Json.decodeFromString(AircraftTerrainContact.serializer(), json.toString())
            val rotation = Matrix4d().rotateX(Math.toRadians(pitch))
            val low = AircraftWheelGeometry.sample(data,rotation).minOf { it.world.y }
            var frame = Matrix4d().translation(0.0,-low,0.0).mul(rotation)
            repeat(240) {
                frame = scene.kernel.plan(data,frame,0.0,1.0,bonePose=spread).frame
                assertTrue(AircraftWheelGeometry.sample(data,frame).all { it.world.y >= -1e-6 }, "$id/$pitch penetrated runway")
            }
            val contacts = scene.kernel.contacts(AircraftWheelGeometry.sample(data,frame))
            assertEquals(data.wheelContacts.size,contacts.rows.size,"$id/$pitch has floating wheel sets")
        }
    }

    @Test fun wheelVolumesSupportTheirWidthAndSettleWithoutFloatingOrPenetrating() {
        val data = solidWheelAircraft()
        val wheel = AircraftWheelGeometry.sample(data, Matrix4d()).first()
        val edge = Scene(listOf(AABB(-2.24, -1.0, -1.3, -2.05, 0.0, -0.7)))
        assertEquals(1, edge.kernel.contacts(listOf(wheel)).rows.size, "tyre edge supports even with its center over a gap")
        val scene = Scene(listOf(floor))
        var frame = mainSupported(data)
        repeat(160) {
            frame = scene.kernel.plan(data, frame, 0.0, 1.0).frame
            assertTrue(AircraftWheelGeometry.sample(data, frame).all { it.world.y >= -1e-6 }, "bogie penetrated runway")
        }
        assertEquals(3, scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame)).rows.size,
            "all authored gear groups must finish on the runway")
        val rolled = AircraftWheelGeometry.sample(data, Matrix4d().rotateZ(0.2)).first()
        val physical = AircraftCollisionSnapshot.create(data, Matrix4d().rotateZ(0.2), 0F).parts[3]
        assertEquals(physical.worldBounds.minY, rolled.world.y, 1e-8, "support and green debug box share the same rotated bottom")
    }

    @Test fun noseFirstSupportSettlesMainWheelsAndSmallStationaryGapsClose() {
        val data = solidWheelAircraft()
        val scene = Scene(listOf(floor))
        val tilted = Matrix4d().rotateX(Math.toRadians(6.0))
        val lowest = AircraftWheelGeometry.sample(data, tilted).minOf { it.world.y }
        var frame = Matrix4d().translate(0.0, -lowest, 0.0).mul(tilted)
        assertEquals(listOf(nose), scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame)).rows.map { it.wheel.group })
        repeat(160) {
            frame = scene.kernel.plan(data, frame, 0.0, 1.0).frame
            assertTrue(AircraftWheelGeometry.sample(data, frame).all { it.world.y >= -1e-6 })
        }
        assertEquals(3, scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame)).rows.size)
        val smallGap = Matrix4d().translate(0.0, 0.08, 0.0)
        assertEquals(smallGap, scene.kernel.plan(data, smallGap, 0.0, 1.0).frame)
        val captured = scene.kernel.plan(data, smallGap, 0.0, 1.0, 0.12)
        assertEquals(3, captured.contacts.rows.size)
        val airborne = Matrix4d().translate(0.0, 0.4, 0.0)
        assertEquals(airborne, scene.kernel.plan(data, airborne, 0.0, 1.0, 0.12).frame)
        assertEquals(smallGap, scene.kernel.plan(data, smallGap, 0.0, 0.0, 0.12).frame)
    }

    private fun mainSupported(data: AircraftTerrainContact, pitch: Double = -6.0): Matrix4d {
        var attitude = pitch
        repeat(8) {
            val frame = Matrix4d().rotateX(Math.toRadians(attitude))
            val lowest = AircraftWheelGeometry.sample(data, frame).filter { it.group == main }.minOf { it.world.y }
            val supported = Matrix4d().translate(0.0, -lowest, 0.0).mul(frame)
            // Each airframe has its own tail-strike limit; a penetrated fuselage must correctly refuse settling.
            if (AircraftCollisionSnapshot.create(data, supported, 0F).parts.first().worldBounds.minY > 0.03)
                return supported
            attitude *= 0.5
        }
        error("No clear main-supported test attitude")
    }

    @Test fun fleetNoseFirstTouchdownBringsMainsDownAtRest() {
        val scene = Scene(listOf(floor))
        for ((id, data) in fits.filterValues { it.wheelContacts.any { wheel -> wheel.group == nose } }) {
            val rotation = Matrix4d().rotateX(Math.toRadians(2.0))
            val lowest = AircraftWheelGeometry.sample(data, rotation).minOf { it.world.y }
            var frame = Matrix4d().translate(0.0, -lowest, 0.0).mul(rotation)
            if (AircraftCollisionSnapshot.create(data, frame, 0F).parts.filter { it.role == com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole.FUSELAGE }.any { it.worldBounds.minY < .02 }) continue
            repeat(180) { frame = scene.kernel.plan(data, frame, 0.0, 1.0).frame }
            val contacts = scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertTrue(contacts.rows.any { it.wheel.group == main }, "$id: main gear remained airborne after nose-first touchdown")
        }
    }

    @Test fun allReviewedFleetLayoutsReachRealSecondarySupportGradually() {
        assertEquals(34, fits.size)
        assertEquals(164, fits.values.sumOf { it.wheelContacts.size })
        val scene = Scene(listOf(floor))
        for ((id, data) in fits) {
            val group = if (data.wheelContacts.any { it.group == nose }) nose else tail
            var frame = mainSupported(data, if (group == tail) 0.0 else -6.0)
            val initial = scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertTrue(initial.rows.any { it.wheel.group == main }, id)
            assertFalse(initial.rows.any { it.wheel.group == group }, "$id secondary must initially be clear")
            var changed = false
            repeat(160) {
                val plan = scene.kernel.plan(data, frame, 0.0, 1.0)
                assertTrue(abs(plan.deltaPitch) <= 0.4000001, id)
                assertTrue(plan.contacts.complete, id)
                changed = changed || plan.deltaPitch != 0.0
                frame = plan.frame
                assertTrue(plan.contacts.rows.any { it.wheel.group == main }, "$id lost main support")
                assertTrue(AircraftWheelGeometry.sample(data, frame).all { it.world.y >= -1e-6 }, "$id tyre crossed floor")
            }
            val final = scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertTrue(changed, "$id never moved")
            assertTrue(final.rows.any { it.wheel.group == group }, "$id secondary floats: ${AircraftWheelGeometry.sample(data, frame).filter { it.group == group }.map { it.world }}")
            val settled = scene.kernel.plan(data, frame, 0.0, 1.0)
            assertEquals(0.0, settled.deltaPitch, "$id should rest at wheel geometry")
            if (group == tail) assertTrue(frame.getEulerAnglesXYZ(org.joml.Vector3d()).x < -0.05, id)
        }
    }

    @Test fun tandemMainBogiesSettleWithoutInventingNoseOrTailWheels() {
        val data = AircraftTerrainContact().apply {
            fuselage.minimum = Vec3(-1.0, 1.2, -5.0)
            fuselage.maximum = Vec3(1.0, 3.0, 5.0)
            landingGear.minimum = Vec3(-0.9, 0.0, -4.9)
            landingGear.maximum = Vec3(0.9, 1.0, 4.9)
            wheelContacts = listOf(
                Vec3(-0.4, 0.0, -3.0), Vec3(0.4, 0.0, -3.0),
                Vec3(-0.4, 0.0, 3.0), Vec3(0.4, 0.0, 3.0),
                Vec3(-4.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0),
            ).mapIndexed { index, point -> AircraftWheelContact().apply {
                id = "tandem_$index"; group = main; position = point
            } }
        }
        val scene = Scene(listOf(floor))
        for (pitch in listOf(-6.0, 6.0)) {
            var frame = mainSupported(data, pitch)
            val initial = scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertEquals(2, initial.rows.size, "one main bogie initially supports the aircraft")
            assertEquals(0.0, scene.kernel.plan(data, frame, 0.0, 0.0).deltaPitch,
                "flight/takeoff inhibition also applies to tandem gear")
            assertEquals(0.0, Scene(emptyList()).kernel.plan(data, frame, 0.0, 1.0).deltaPitch)
            var changed = false
            repeat(160) {
                val plan = scene.kernel.plan(data, frame, 0.0, 1.0)
                changed = changed || plan.deltaPitch != 0.0
                assertTrue(abs(plan.deltaPitch) <= 0.4000001)
                assertTrue(plan.contacts.complete)
                frame = plan.frame
                assertTrue(AircraftWheelGeometry.sample(data, frame).all { it.world.y >= -1e-6 },
                    "supported tyres must not move through the floor")
            }
            assertTrue(changed)
            val final = scene.kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertEquals(6, final.rows.size, "both main bogies and outriggers reach the floor")
            assertEquals(0.0, scene.kernel.plan(data, frame, 0.0, 1.0).deltaPitch)
        }
    }

    @Test fun actualMainFirstLandingThenPivotOnlyNoseTouchdownEmitsEachGroupOnce() {
        val data = fits.getValue("mig_15bis")
        val scene = Scene(listOf(floor))
        var frame = Matrix4d().translate(0.0, 0.9, 0.0).mul(mainSupported(data))
        val events = AircraftWheelContactEvents()
        var previous = emptyMap<String, Vec3>()
        val emitted = mutableListOf<com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelTouchdown>()
        var supported = false
        for (tick in 1L..180L) {
            val initial = AircraftWheelGeometry.sample(data, frame)
            val requested = if (supported) Vec3.ZERO else Vec3(0.0, -0.15, 0.0)
            val expected = initial.associate { it.id to it.world.add(requested) }
            val motion = scene.move(data, frame, requested, supported)
            assertFalse(motion.bodyContact)
            frame = Matrix4d().translate(motion.movement.x, motion.movement.y, motion.movement.z).mul(frame)
            val before = AircraftWheelGeometry.sample(data, frame)
            val plan = scene.kernel.plan(data, frame, 0.0, if (supported) 1.0 else 0.0)
            frame = plan.frame
            val after = AircraftWheelGeometry.sample(data, frame)
            val rotatedExpected = AircraftWheelGeometry.addRotation(expected, before, after)
            val speeds = AircraftWheelGeometry.closingSpeeds(plan.contacts.rows, previous,
                initial.associate { it.id to it.world }, rotatedExpected)
            val contacts = plan.contacts.rows.groupBy { it.wheel.group }.mapValues { it.value.map { row -> row.point } }
            val now = events.sample(tick, motion.complete && plan.contacts.complete, contacts, speeds)
            for (event in now) {
                assertTrue(event.contacts.all { abs(it.y) < 1e-7 })
                if (event.group == nose) {
                    assertEquals(Vec3.ZERO, requested, "nose event must arise from rotation alone")
                    assertTrue(event.sinkSpeedBlocksPerTick > 0.0)
                    assertTrue(plan.deltaPitch > 0.0)
                }
            }
            emitted.addAll(now)
            supported = contacts[main]?.isNotEmpty() == true
            previous = after.associate { it.id to it.world }
        }
        assertEquals(listOf(main, nose), emitted.map { it.group })
        assertEquals(listOf(1L, 2L), emitted.map { it.sequence })
        assertTrue(emitted[1].serverTick > emitted[0].serverTick)
        assertEquals(0.15, emitted[0].sinkSpeedBlocksPerTick, 1e-8)
    }

    @Test fun unknownTerrainFloorHolesBodyObstructionsAndHighSpeedDoNotSettle() {
        val data = fits.getValue("mig_15bis")
        val frame = mainSupported(data)
        assertEquals(0.0, Scene(listOf(floor)).kernel.plan(data, frame, 0.0, 0.0).deltaPitch)
        assertEquals(0.0, Scene(emptyList()).kernel.plan(data, frame, 0.0, 1.0).deltaPitch)
        val body = AircraftCollisionSnapshot.create(data, frame, 0F).parts.first().worldBounds
        val obstacle = AABB(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ)
        assertEquals(0.0, Scene(listOf(floor, obstacle)).kernel.plan(data, frame, 0.0, 1.0).deltaPitch)
        val unknown = AircraftWheelSupportKernel { _, _, _ -> AircraftWheelSupportKernel.Probe(null, false) }
        assertEquals(0.0, unknown.plan(data, frame, 0.0, 1.0).deltaPitch)
        assertFalse(unknown.plan(data, frame, 0.0, 1.0).contacts.complete)
        val mainOnly = AABB(-200.0, -10.0, -200.0, 200.0, 0.0, 0.0)
        assertEquals(0.0, Scene(listOf(mainOnly)).kernel.plan(data, frame, 0.0, 1.0).deltaPitch)
    }

    @Test fun exactPointsOutsideBroadVolumesRemainTerrainSamples() {
        for ((id, group) in listOf("mig19" to nose, "il_76m" to nose, "m_50a" to main)) {
            val data = fits.getValue(id)
            val outside = data.wheelContacts.filter { it.group == group }.first {
                it.position.x !in data.landingGear.minimum.x..data.landingGear.maximum.x ||
                    it.position.z !in data.landingGear.minimum.z..data.landingGear.maximum.z
            }
            val frame = Matrix4d().translate(0.0, -outside.position.y, 0.0)
            val contacts = Scene(listOf(floor)).kernel.contacts(AircraftWheelGeometry.sample(data, frame))
            assertTrue(contacts.rows.any { it.wheel.id == outside.id }, id)
            assertEquals(2, AircraftCollisionSnapshot.create(data, frame, 0F).parts.size)
        }
    }

    @Test fun wheelBackedHalfAndFullBlockTraversalRetainsMomentumAndHouseDamage() {
        for (id in listOf("mig_15bis", "mig_19s", "il_76m", "mig19")) {
            val data = fits.getValue(id)
            val scene = Scene(listOf(floor))
            var settled = mainSupported(data)
            repeat(160) { settled = scene.kernel.plan(data, settled, 0.0, 1.0).frame }
            for (height in listOf(0.5, 1.0)) {
                val edge = maxOf(FixedWingContactSweep.Body(AircraftCollisionSnapshot.create(data, settled, 0F).terrainInfos().first().getOBB()).bounds.maxZ,
                    AircraftWheelGeometry.sample(data, settled).maxOf { it.world.z }) + 1.0
                val step = AABB(-100.0, 0.0, edge, 100.0, height, 200.0)
                var frame = Matrix4d(settled); var stepped = false
                repeat(30) {
                    val result = Scene(listOf(floor, step)).move(data, frame, Vec3(0.0, -0.012, 0.5), true)
                    assertTrue(result.complete, "$id $height")
                    assertFalse(result.bodyContact, "$id height=$height tick=$it $result")
                    assertEquals(0.5, result.movement.z, 1e-7, "$id height=$height tick=$it")
                    assertEquals(0.5, result.velocity.z, 1e-7)
                    frame = Matrix4d().translate(result.movement.x, result.movement.y, result.movement.z).mul(frame)
                    assertTrue(AircraftWheelGeometry.sample(data, frame).all { wheel ->
                        wheel.world.y >= -1e-6 && (wheel.world.z < edge - 1e-6 || wheel.world.y >= height - 1e-6)
                    }, "$id tyre crossed step tick=$it height=$height")
                    stepped = stepped || result.gearStepUsed
                }
                assertTrue(stepped, "$id height=$height")
            }
            val wallZ = FixedWingContactSweep.Body(AircraftCollisionSnapshot.create(data, settled, 0F).terrainInfos().first().getOBB()).bounds.maxZ + 0.2
            val wall = AABB(-100.0, 0.0, wallZ, 100.0, 30.0, wallZ + 1.0)
            var frame = Matrix4d(settled); var crashed = false
            repeat(30) {
                if (!crashed) {
                    val result = Scene(listOf(floor, wall)).move(data, frame, Vec3(0.0, -0.012, 1.0), true)
                    crashed = result.bodyContact && result.damage.destructive
                    frame = Matrix4d().translate(result.movement.x, result.movement.y, result.movement.z).mul(frame)
                }
            }
            assertTrue(crashed, "$id house must retain fuselage damage")
        }
    }

    @Test fun rotationAboutMainMatchesNativePoseAndPreservesItsContact() {
        for (yaw in listOf(0.0, 73.0, -152.0)) for (roll in listOf(-14.0, 0.0, 14.0)) {
            val position = Vec3(20.0, 75.0, -30.0)
            val pivot = 2.6
            fun native(pos: Vec3, pitch: Double) = Matrix4d().translate(pos.x, pos.y + pivot, pos.z)
                .rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll))
                .translate(0.0, -pivot, 0.0)
            val old = native(position, -6.0)
            val anchor = Vec3(-1.5, 0.0, -1.0)
            val next = AircraftWheelGeometry.pitchAround(old, anchor, roll, 0.3)
            val correction = AircraftWheelGeometry.originCorrection(old, next, pivot, roll, 0.3)
            val actual = native(position.add(correction), -5.7)
            for (point in listOf(anchor, Vec3.ZERO, Vec3(3.0, 2.0, 7.0)))
                assertEquals(0.0, AircraftWheelGeometry.transform(actual, point).distanceTo(AircraftWheelGeometry.transform(next, point)), 1e-10)
            assertEquals(0.0, AircraftWheelGeometry.transform(old, anchor).distanceTo(AircraftWheelGeometry.transform(actual, anchor)), 1e-10)
        }
    }

    @Test fun pointSupportHandlesShallowPoseOverlapButCannotShieldSteepBodyOrConsumeUnknownProof() {
        val scene = Scene(listOf(floor))
        val point = AircraftWheelGeometry.pointInfo(Vec3(0.0, -0.02, 0.0))
        val result = scene.query(listOf(point), Vec3(0.0, -0.01, 0.5), Vec3.ZERO).first
        assertEquals(0.02, result.contact!!.penetrationDepth, 1e-8)
        assertEquals(Vec3(0.0, 1.0, 0.0), result.contact!!.normal)
        val steep = AircraftWheelGeometry.pointInfo(Vec3(0.0, -0.02, 0.0), Quaterniond().rotateX(Math.toRadians(60.0)))
        assertNull(scene.query(listOf(steep), Vec3(0.0, -0.1, 0.0), Vec3.ZERO).first.contact)
        val resting = AircraftWheelGeometry.pointInfo(Vec3.ZERO)
        assertNull(scene.query(listOf(resting), Vec3(0.0, 0.0, 1.0), Vec3.ZERO).first.contact)
        val box = point.getOBB(); val body = FixedWingContactSweep.Body(box)
        val movement = Vec3(0.0, -0.01, 0.0); val raw = body.sweep(movement, floor)!!
        val neighbor = AABB(10.0, 0.0, 10.0, 11.0, 1.0, 11.0)
        val unknown = AircraftTerrainContactQuery.resolve(body, box, true, movement, floor, raw,
            listOf(floor, neighbor), FixedWingContactSurface.Budget(0))
        assertFalse(unknown.complete); assertNull(unknown.contact)
    }

    @Test fun independentEdgesSuppressSpawnRestDuplicatesGapsAndBriefBounces() {
        val events = AircraftWheelContactEvents()
        val point = Vec3(1.0, 2.0, 3.0)
        val contacts = mapOf(main to listOf(point), nose to listOf(point))
        val speeds = mapOf(main to 0.2, nose to 0.03)
        assertTrue(events.sample(1, true, contacts, speeds).isEmpty())
        assertTrue(events.sample(2, true, contacts, speeds).isEmpty())
        events.sample(3, true, emptyMap(), emptyMap())
        events.sample(4, true, emptyMap(), emptyMap())
        assertEquals(2, events.sample(5, true, contacts, speeds).size)
        assertTrue(events.sample(5, true, contacts, speeds).isEmpty())
        events.sample(6, true, emptyMap(), emptyMap())
        assertTrue(events.sample(7, true, contacts, speeds).isEmpty())
        events.sample(8, true, emptyMap(), emptyMap())
        events.sample(9, true, emptyMap(), emptyMap())
        assertTrue(events.sample(10, false, contacts, speeds).isEmpty())
        assertTrue(events.sample(11, true, contacts, speeds).isEmpty())
        events.sample(12, true, emptyMap(), emptyMap()); events.sample(13, true, emptyMap(), emptyMap())
        assertTrue(events.sample(15, true, contacts, speeds).isEmpty())
    }

    @Test fun speedBlendKeepsHigherSpeedAttitudeAndSmoothlyEnablesSlowSettling() {
        val handling = FixedWingHandlingProfile.GAME_JET
        val strategy = FixedWingFlightStrategy(MiG19FixedWingProfile.PROFILE, handling)
        val reference = handling.takeoffHandling?.referenceSpeedMps ?: handling.liftReferenceSpeedMps
        assertEquals(1.0, strategy.groundGearSettleWeight(0.0))
        assertEquals(1.0, strategy.groundGearSettleWeight(reference * 0.35), 1e-10)
        assertEquals(0.5, strategy.groundGearSettleWeight(reference * 0.50), 1e-10)
        assertEquals(0.0, strategy.groundGearSettleWeight(reference * 0.65), 1e-10)
        assertEquals(0.0, strategy.groundGearSettleWeight(reference * 1.5))
    }

    @Test fun acceptedPhysicalPitchPersistsInFlightModelWithoutResettingMomentumOrPilotState() {
        val model = FixedWingFlightModel()
        model.reset(21.0, -6.0, 4.0)
        model.step(1, 0.0, 0.0, 8.0, true, true, throttleAxis = 1.0,
            pitchDelta = 0.2, rollDelta = 0.3, rudderInput = 0.1)
        val state = listOf(model.velocityX, model.velocityY, model.velocityZ, model.throttle,
            model.elevator, model.aileron, model.rudder, model.yawDegrees, model.rollDegrees)
        val accepted = model.pitchDegrees + 0.35
        model.acceptGroundPitch(accepted)
        val after = listOf(model.velocityX, model.velocityY, model.velocityZ, model.throttle,
            model.elevator, model.aileron, model.rudder, model.yawDegrees, model.rollDegrees)
        state.indices.forEach { assertEquals(state[it], after[it], 1e-9) }
        assertEquals(accepted, model.pitchDegrees, 1e-9)
        assertEquals(0.0, model.pitchRateDegreesPerSecond)
        model.step(2, model.velocityX, 0.0, model.velocityZ, true, false)
        assertEquals(accepted, model.pitchDegrees, 0.05, "next flight tick must not restore the pre-contact pose")
    }
}
