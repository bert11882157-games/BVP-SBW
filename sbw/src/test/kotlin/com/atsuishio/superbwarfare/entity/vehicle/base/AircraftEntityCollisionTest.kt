package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.tools.OBB
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.min

class AircraftEntityCollisionTest {
    private data class Fit(val id: String, val definition: AircraftTerrainContact, val pivot: Double)
    private val fits = Json.parseToJsonElement(javaClass.getResourceAsStream("/aircraft_entity_collision_fits.json")!!
        .bufferedReader().use { it.readText() }).jsonObject.map { (id, value) ->
        Fit(id, Json.decodeFromString(AircraftTerrainContact.serializer(), value.jsonObject.getValue("contact").toString()),
            value.jsonObject.getValue("rotateOffsetHeight").jsonPrimitive.double)
    }

    private fun snapshot(fit: Fit, gear: Float = 0F, position: Vec3 = Vec3.ZERO,
                         yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0): AircraftCollisionSnapshot {
        val frame = Matrix4d().translate(position.x, position.y + fit.pivot, position.z)
            .rotateY(yaw).rotateX(pitch).rotateZ(roll).translate(0.0, -fit.pivot, 0.0)
        return AircraftCollisionSnapshot.create(fit.definition, frame, gear)
    }

    private fun move(snapshot: AircraftCollisionSnapshot, bounds: AABB, movement: Vec3): Vec3 =
        AircraftEntityMovement.resolve(movement, bounds, snapshot.activeObbs()) { axis, _ -> axis }

    @Test fun allAcceptedAircraftExposeExactlyTwoPartsAndTightActiveBounds() {
        assertEquals(45, fits.size)   // saab_105 removed
        for (fit in fits) {
            val snapshot = snapshot(fit, position = Vec3(2048.5, -59.0, 3072.5), yaw = 0.37, pitch = 0.13, roll = -0.09)
            assertEquals(listOf(AircraftCollisionRole.FUSELAGE, AircraftCollisionRole.LANDING_GEAR), snapshot.parts.map { it.role })
            assertTrue(snapshot.parts.all { it.active })
            val union = snapshot.parts.map { it.worldBounds }.reduce(AABB::minmax)
            assertEquals(union, snapshot.queryBounds, fit.id)
            val infos = snapshot.terrainInfos()
            for (i in infos.indices) {
                val terrain = FixedWingContactSweep.Body(infos[i].getOBB()).bounds
                val physical = snapshot.parts[i].worldBounds
                assertEquals(physical.minX, terrain.minX, 1e-9)
                assertEquals(physical.maxY, terrain.maxY, 1e-9)
                assertEquals(physical.maxZ, terrain.maxZ, 1e-9)
                assertEquals(8, snapshot.parts[i].worldVertices.size)
            }
        }
    }

    @Test fun ordinaryEntityMovementHitsBodyAndGearButNotTheEnvelopeInset() {
        for (fit in fits) {
            val snapshot = snapshot(fit)
            for (part in snapshot.parts) {
                val bounds = part.worldBounds
                val y = if (part.role == AircraftCollisionRole.FUSELAGE) bounds.center.y else
                    (bounds.minY + min(bounds.maxY, fit.definition.fuselage.minimum.y)) * 0.5
                val probe = AABB(bounds.minX - 0.215, y - 0.015, bounds.center.z - 0.015,
                    bounds.minX - 0.185, y + 0.015, bounds.center.z + 0.015)
                val result = move(snapshot, probe, Vec3(0.4, 0.0, 0.0))
                assertEquals(0.185, result.x, 1e-7, "${fit.id}:${part.role}")
            }
            val body = snapshot.parts[0].worldBounds
            val gear = snapshot.parts[1].worldBounds
            val gap = gear.minX - body.minX
            val half = min(0.015, gap * 0.1)
            val x = (body.minX + gear.minX) * 0.5
            val y = (gear.minY + min(gear.maxY, body.minY)) * 0.5
            val z = gear.center.z
            val probe = AABB(x - half, y - half, z - half, x + half, y + half, z + half)
            assertTrue(snapshot.queryBounds.intersects(probe))
            val requested = Vec3(0.0, 0.0, 0.1)
            assertEquals(requested, move(snapshot, probe, requested), fit.id)
            assertNull(snapshot.clip(Vec3(x, y, z - 0.1), Vec3(x, y, z + 0.1)), fit.id)
        }
    }

    @Test fun deploymentChangesPhysicalMovementAndQueryBoundsWithFixedGearPreserved() {
        for (fit in fits) for (fraction in listOf(0F, 0.5F, 1F)) {
            val snapshot = snapshot(fit, fraction)
            val active = !fit.definition.retractableGear || fraction == 0F
            assertEquals(active, snapshot.parts[1].active, fit.id)
            val gear = snapshot.parts[1].worldBounds
            val y = (gear.minY + min(gear.maxY, fit.definition.fuselage.minimum.y)) * 0.5
            val probe = AABB(gear.minX - 0.215, y - 0.015, gear.center.z - 0.015,
                gear.minX - 0.185, y + 0.015, gear.center.z + 0.015)
            assertEquals(if (active) 0.185 else 0.4, move(snapshot, probe, Vec3(0.4, 0.0, 0.0)).x, 1e-7)
            if (!active) assertEquals(snapshot.parts[0].worldBounds, snapshot.queryBounds)
        }
    }

    @Test fun rotatedPartEnvelopeCornersRemainEmptyAndMovementCanLeaveContact() {
        val snapshot = snapshot(fits.first { it.id == "mig_19s" }, yaw = Math.PI / 4)
        val body = snapshot.parts[0].worldBounds
        val empty = AABB(body.maxX - 0.06, body.center.y - 0.01, body.maxZ - 0.06,
            body.maxX - 0.04, body.center.y + 0.01, body.maxZ - 0.04)
        assertTrue(body.intersects(empty))
        assertEquals(Vec3(0.01, 0.0, 0.0), move(snapshot, empty, Vec3(0.01, 0.0, 0.0)))
        val flat = snapshot(fits.first { it.id == "mig_19s" })
        val minX = flat.parts[0].worldBounds.minX
        val touching = AABB(minX - 0.03, 1.0, -0.015, minX, 1.03, 0.015)
        assertEquals(Vec3(-0.1, 0.0, 0.0), move(flat, touching, Vec3(-0.1, 0.0, 0.0)))
    }

    @Test fun nativeBlockConstraintsAndPhysicalPartsShareTheActualAxisPath() {
        val snapshot = snapshot(fits.first { it.id == "mig_19s" })
        val body = snapshot.parts[0].worldBounds
        val probe = AABB(body.minX - 0.3, 1.0, -0.015, body.minX - 0.27, 1.03, 0.015)
        val wall = Shapes.create(AABB(body.minX - 0.1, 0.0, -10.0, body.minX, 4.0, 10.0))
        val result = AircraftEntityMovement.resolve(Vec3(0.5, -0.01, 0.2), probe, snapshot.activeObbs()) { axis, box ->
            Vec3(Shapes.collide(Direction.Axis.X, box, listOf(wall), axis.x),
                Shapes.collide(Direction.Axis.Y, box, listOf(wall), axis.y),
                Shapes.collide(Direction.Axis.Z, box, listOf(wall), axis.z))
        }
        assertEquals(0.17, result.x, 1e-7)
        assertEquals(0.2, result.z, 1e-7)
    }

    @Test fun nativeSteppingCanClimbALowPhysicalPart() {
        val definition = AircraftTerrainContact()
        definition.fuselage.minimum = Vec3(-1.0, 1.0, -1.0)
        definition.fuselage.maximum = Vec3(1.0, 2.0, 1.0)
        definition.landingGear.minimum = Vec3(-0.8, 0.0, -0.8)
        definition.landingGear.maximum = Vec3(0.8, 0.4, 0.8)
        val snapshot = AircraftCollisionSnapshot.create(definition, Matrix4d(), 0F)
        val probe = AABB(-1.0, 0.0, -0.015, -0.97, 0.03, 0.015)
        val result = VehicleCollisionSolver.collide(Vec3(0.4, -0.01, 0.0), probe, true, 0.6) { movement, bounds ->
            move(snapshot, bounds, movement)
        }
        assertEquals(0.4, result.x, 1e-7)
        assertEquals(0.4, result.y, 1e-7)
    }

    @Test fun orientedPairSweepsIdentifyGearOnlyAndBodyContactsWithoutEndpointTunneling() {
        val snapshot = snapshot(fits.first { it.id == "mig_19s" })
        val parts = snapshot.parts.map { VehicleEntityContacts.Part(it.toObb(), it.role == AircraftCollisionRole.FUSELAGE) }
        for (y in listOf(0.3, 1.5)) {
            val target = OBB(Vector3d(3.5, y, 0.0), Vector3d(0.1, 0.1, 0.1),
                Quaterniond().rotateY(0.25), OBB.Part.BODY)
            val hit = VehicleEntityContacts.find(parts, listOf(VehicleEntityContacts.Part(target, true)), Vec3(5.0, 0.0, 0.0))
            assertNotNull(hit)
            assertEquals(y > 1.0, hit!!.ownBody)
            assertTrue(hit.otherBody)
            assertTrue(hit.fraction in 0.0..0.5)
        }
    }

    @Test fun sectionIndexFindsExtendedPartsAcrossOriginsAndRemovesOldBounds() {
        val index = PhysicalBoundsIndex<Any>()
        val owner = Any()
        val old = AABB(-36.0, -60.0, -48.0, 5.0, -54.0, 3.0)
        index.update(owner, old)
        val end = AABB(-35.0, -59.0, -47.0, -34.0, -58.0, -46.0)
        assertEquals(listOf(owner), index.query(end))
        assertEquals(listOf(owner), index.query(old))
        val moved = old.move(100.0, 0.0, 100.0)
        index.update(owner, moved)
        assertTrue(index.query(end).isEmpty())
        assertEquals(listOf(owner), index.query(moved))
        index.remove(owner)
        assertTrue(index.query(moved).isEmpty())
    }
}
