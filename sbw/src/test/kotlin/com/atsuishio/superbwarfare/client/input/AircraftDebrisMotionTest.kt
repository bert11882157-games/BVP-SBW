package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics
import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisMotion
import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisContact
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AircraftDebrisMotionTest {
    private val slop = WreckDebrisPhysics.CONTACT_SLOP

    @Test fun shallowFloorPenetrationDoesNotBecomeAFalseSideWall() {
        val floor = Shapes.box(-8.0, -1.0, -8.0, 8.0, 0.0, 8.0)
        val from = Vec3(0.0, -.02, 0.0)
        val to = from.add(.8, -.03, 0.0)
        val raw = floor.clip(from, to, BlockPos.ZERO)!!
        assertTrue(raw.isInside)
        assertEquals(Direction.WEST, raw.direction, "Vanilla inside ray reports travel-opposing normal")
        val contact = AircraftDebrisContact.sweep(from, to) { a, b -> floor.clip(a,b,BlockPos.ZERO) }!!
        assertEquals(Vec3(0.0,1.0,0.0), contact.normal)
        assertEquals(0.0, contact.position.y, 1e-9)
        val part=AircraftDebrisMotion(from,Vec3(.8,-.03,0.0),Quaternionf(),Vec3.ZERO,0.0)
        part.tick({ a,b -> AircraftDebrisContact.relaxedSweep(a,b) { c,d -> floor.clip(c,d,BlockPos.ZERO) } })
        assertTrue(part.velocity.x > .7, "Floor recovery preserves tangential inertia")
        assertTrue(part.position.x > .5, "Fragment continues across the surface")
        val wall = Shapes.box(.5, -8.0, -8.0, 1.5, 8.0, 8.0)
        val embedded = Vec3(.52, 0.0, 0.0)
        assertNull(AircraftDebrisContact.sweep(embedded, embedded.add(.8,-.03,0.0)) { a,b -> wall.clip(a,b,BlockPos.ZERO) },
            "An embedded sample with no reachable surface is skipped; its travel-opposing normal would pin the piece")
        val outside = Vec3(.2, 0.0, 0.0)
        val wallContact = AircraftDebrisContact.sweep(outside, outside.add(.8,-.03,0.0)) {
            a,b -> wall.clip(a,b,BlockPos.ZERO)
        }!!
        assertEquals(Vec3(-1.0,0.0,0.0), wallContact.normal,
            "Recovery must not turn a genuine wall strike into a floor")
    }

    private fun ground(from: Vec3, to: Vec3): AircraftDebrisMotion.Contact? =
        AircraftDebrisContact.relaxedSweep(from,to) { a,b ->
            Shapes.box(-100.0,-1.0,-100.0,100.0,0.0,100.0).clip(a,b,BlockPos.ZERO)
        }

    @Test fun fragmentsDeflectThenScrapeWithCoulombFrictionAndExpireTenSecondsAfterContact() {
        val part=AircraftDebrisMotion(Vec3(0.0,2.0,0.0),Vec3(.8,-.5,0.0),Quaternionf(),Vec3.ZERO,0.0,9.80665/400.0,2)
        while(!part.grounded && part.age<100) part.tick(::ground)
        assertEquals(0,part.bounces)
        assertTrue(part.grounded)
        val impactX=part.position.x
        val impactSpeed=part.velocity.x
        assertTrue(impactSpeed>.5,"tangential momentum survives initial contact")
        var previous=impactSpeed
        val limit=WreckDebrisPhysics.FUSELAGE_FRICTION*9.80665/400.0
        repeat(15) {
            part.tick(::ground)
            assertTrue(part.position.y >= -slop - .01 && part.position.y <= .01)
            // Constant friction deceleration plus a small gouging loss; never an exponential stall.
            assertTrue(previous - part.velocity.x <= limit + previous * .02 + 1e-9)
            previous = part.velocity.x
        }
        assertTrue(part.position.x>impactX+5,"wreck grinds along the surface")
        assertTrue(part.velocity.x in 0.0..impactSpeed)
        while(part.groundedTicks<199)part.tick(::ground)
        assertFalse(part.expired)
        part.tick(::ground);assertTrue(part.expired)
    }

    @Test fun wallCollisionCannotFreezeDebrisInMidair() {
        val part=AircraftDebrisMotion(Vec3(0.0,20.0,0.0),Vec3(1.0,0.0,.5),Quaternionf(),Vec3(.02,.01,0.0),0.0)
        part.tick({ from,to -> AircraftDebrisMotion.Contact(from.lerp(to,.5),Vec3(-1.0,0.0,0.0)) })
        val y=part.position.y;val z=part.position.z
        assertFalse(part.grounded)
        repeat(10){part.tick({ _,_->null })}
        assertTrue(part.position.y<y-1)
        assertTrue(part.position.z>z+1)
    }

    @Test fun slidingOffALedgeResumesGravityAndWingExpiresTenSecondsAfterImpact() {
        val part=AircraftDebrisMotion(Vec3(0.0,.1,0.0),Vec3(.7,-.2,0.0),Quaternionf(),Vec3.ZERO,0.0)
        part.tick(::ground)
        assertTrue(part.impacted);assertFalse(part.expired)
        val y=part.position.y
        repeat(10){part.tick({ _,_->null })}
        assertFalse(part.grounded);assertTrue(part.position.y<y-1)
        repeat(189){part.tick({ _,_->null })}
        assertFalse(part.expired)
        part.tick({ _,_->null });assertTrue(part.expired)
    }

    private class Terrain(val shape: VoxelShape) {
        val surface = AircraftDebrisMotion.Surface { from, depth ->
            AircraftDebrisContact.surface(from, depth) { a, b -> shape.clip(a, b, BlockPos.ZERO) }
        }
        fun tick(part: AircraftDebrisMotion) = part.tick({ from, to ->
            part.sweep(from, to) { a, b -> AircraftDebrisContact.relaxedSweep(a, b, 4.0) { c, d -> shape.clip(c, d, BlockPos.ZERO) } }
        }, surface)
    }
    private val flat = Terrain(Shapes.box(-300.0, -1.0, -30.0, 300.0, 0.0, 30.0))
    private fun lowest(part: AircraftDebrisMotion) = part.position.y + part.supports.minOf { part.offset(it).y }

    @Test fun fortyBlocksPerSecondSlidesTensOfBlocksAndStopsWithinSeconds() {
        val half = Vec3(1.0, .6, 3.0)
        val part = AircraftDebrisMotion(Vec3(0.0, .6 - slop + .02, 0.0), Vec3(2.0, 0.0, 0.0), Quaternionf(), Vec3.ZERO, 0.0,
            halfExtents = half, rest = WreckDebrisPhysics.Rest.FUSELAGE)
        flat.tick(part)
        val start = part.velocity.horizontalDistance()
        assertTrue(start > 1.8, "A grazing touchdown keeps nearly all momentum")
        repeat(20) { flat.tick(part) }
        assertTrue(part.velocity.horizontalDistance() > start * .5, "One second of sliding keeps most of the speed")
        var ticks = 21
        while (part.velocity.horizontalDistanceSqr() > 0 && ticks < 400) { flat.tick(part); ticks++ }
        assertTrue(ticks < 120, "The slide ends within six seconds ($ticks ticks)")
        assertTrue(part.position.x in 30.0..90.0, "Slide distance ${part.position.x}")
        assertEquals(0, part.pinnedTicks)
        assertTrue(part.maxPenetration <= slop + 1e-4)
        assertEquals(1, WreckDebrisPhysics.verticalAxis(part.orientation, Vec3(0.0, 1.0, 0.0)), "A belly landing stays on the belly")
    }

    @Test fun capturedInsideTerrainIsLiftedAndSlidesFreely() {
        val part = AircraftDebrisMotion(Vec3(0.0, -.8, 0.0), Vec3(1.0, -.3, 0.0), Quaternionf(), Vec3.ZERO, 0.0,
            halfExtents = Vec3(1.0, .5, 2.0), rest = WreckDebrisPhysics.Rest.FUSELAGE)
        part.placeOn(flat.surface)
        assertEquals(-slop, lowest(part), 1e-3, "Fully buried capture digs out to the relaxed overlap")
        assertEquals(part.position, part.previousPosition, "Placement is not interpolated from the buried pose")
        repeat(20) { flat.tick(part) }
        assertTrue(part.position.x > 8, "The piece keeps its inertia instead of lodging")
        assertEquals(0, part.pinnedTicks)
        assertTrue(lowest(part) >= -slop - 1e-3)
    }

    @Test fun rotationIntoTheGroundIsLiftedNeverPinned() {
        // A tumbling panel whose spin carries a corner deep into the floor between sweeps.
        val part = AircraftDebrisMotion(Vec3(0.0, .5, 0.0), Vec3(.6, 0.0, 0.0), Quaternionf(), Vec3(0.0, 0.0, .5), 0.0,
            halfExtents = Vec3(3.0, .1, 1.0), rest = WreckDebrisPhysics.Rest.WING)
        repeat(60) {
            flat.tick(part)
            assertTrue(lowest(part) >= -slop - 1e-3, "tick ${part.age}: ${lowest(part)}")
        }
        assertTrue(part.position.x > 4, "The slide survives the tumble: ${part.position}")
        assertEquals(0, part.pinnedTicks)
    }

    @Test fun lowStepsLiftASlidingPieceButWallsDoNot() {
        val step = Terrain(Shapes.or(Shapes.box(-100.0, -1.0, -100.0, 100.0, 0.0, 100.0),
            Shapes.box(4.0, 0.0, -100.0, 100.0, 1.0, 100.0)))
        val rider = AircraftDebrisMotion(Vec3(0.0, .3 - slop, 0.0), Vec3(.6, 0.0, 0.0), Quaternionf(), Vec3.ZERO, 0.0,
            halfExtents = Vec3(.5, .3, .5))
        repeat(30) { step.tick(rider) }
        assertTrue(rider.position.x > 4.6, "Slide continues over a one-block rise: ${rider.position}")
        assertTrue(lowest(rider) > 1 - slop - .05, "Piece rides on top of the step")
        val wall = Terrain(Shapes.or(Shapes.box(-100.0, -1.0, -100.0, 100.0, 0.0, 100.0),
            Shapes.box(4.0, 0.0, -100.0, 5.0, 3.0, 100.0)))
        val blocked = AircraftDebrisMotion(Vec3(0.0, .3 - slop, 0.0), Vec3(.6, 0.0, 0.0), Quaternionf(), Vec3.ZERO, 0.0,
            halfExtents = Vec3(.5, .3, .5))
        repeat(30) { wall.tick(blocked) }
        assertTrue(blocked.position.x < 4 && blocked.position.y < .5, "A three-block wall is not climbed")
    }

    @Test fun wingOnItsEdgeTopplesFlatAndFuselageLeavesItsCutEnd() {
        val up = Vec3(0.0, 1.0, 0.0)
        val wing = AircraftDebrisMotion(Vec3(0.0, 2.5, 0.0), Vec3.ZERO, Quaternionf().rotationZ(Math.toRadians(70.0).toFloat()),
            Vec3.ZERO, 0.0, halfExtents = Vec3(2.0, .15, 1.0), rest = WreckDebrisPhysics.Rest.WING,
            friction = WreckDebrisPhysics.WING_FRICTION)
        repeat(200) { flat.tick(wing) }
        assertTrue(abs(wing.orientation.transform(Vector3f(0f, 1f, 0f)).y) > .995f, "A wing ends flat on either face")
        assertTrue(lowest(wing) in -slop - .03 .. .03)
        assertTrue(wing.restAge > 0, "The toppled wing comes to rest")
        for (half in listOf(Vec3(1.0, 1.0, 2.5), Vec3(1.2, 1.0, .8))) {
            val section = AircraftDebrisMotion(Vec3(0.0, half.z + .05, 0.0), Vec3.ZERO,
                Quaternionf().rotationX(Math.toRadians(90.0).toFloat()), Vec3.ZERO, 0.0, halfExtents = half,
                rest = WreckDebrisPhysics.Rest.FUSELAGE)
            assertEquals(2, WreckDebrisPhysics.verticalAxis(section.orientation, up))
            repeat(200) { flat.tick(section) }
            assertNotEquals(2, WreckDebrisPhysics.verticalAxis(section.orientation, up), "Never rests on the cut end")
            assertTrue(abs(section.orientation.transform(Vector3f(0f, 0f, 1f)).y) < .05f)
            assertTrue(lowest(section) in -slop - .03 .. .03)
        }
    }
}
