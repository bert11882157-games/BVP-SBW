package com.atsuishio.superbwarfare.api.vehicle.deck

import com.google.gson.JsonParser
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The LCAC's cargo well is a carrier-style deck (BVP FittedDeckBoatEntity, data/berts_vehicle_pack/sbw/decks/lcac.json from
 * tools/vehgen/lcac_deck.py): vehicles drive on through the two ramps, stand on the flat well floor between the side
 * structures, and keep their place in the craft's frame while it moves and turns. The DeckCarry math is DeckPose.localX /
 * worldX (DeckCarry.move places a rider at the new pose's world point of its old local point and adds the yaw change).
 * Skipped when the BVP sources are not beside this project.
 */
class LcacCargoDeckTest {
    private val file = File(System.getProperty("bvp.root") ?: "../bvp",
        "src/generated/resources/data/berts_vehicle_pack/sbw/decks/lcac.json")

    private fun deck(): DeckSurface {
        assumeTrue(file.isFile, "BVP sources not present")
        return DeckSurface.parse(file.reader().use { JsonParser.parseReader(it).asJsonObject })
    }

    private fun topAtX(deck: DeckSurface, x: Double, z: Double) = deck.topAt(x, z)

    @Test
    fun wellIsOneFlatFloorWithAWallEachSide() {
        val d = deck()
        val floor = d.deckHeight
        // the well: |x| < 3.4 from z -5 to 8.5 is one height, no hole
        var z = -5.0
        while (z <= 8.5) {
            var x = -3.4
            while (x <= 3.4) {
                assertEquals(floor, topAtX(d, x, z), 1e-6, "well floor at $x $z")
                x += 0.25
            }
            z += 0.25
        }
        // the side structures are walls well above a vehicle's step
        for (side in doubleArrayOf(-5.0, 5.0)) assertTrue(topAtX(d, side, 1.0) > floor + 2.0, "wall at x=$side")
        // the well is wide enough for a vehicle ( >= 6.5 blocks between the walls)
        var left = 0.0
        while (!(topAtX(d, left, 1.0) > floor + 0.5)) left += 0.25
        var right = 0.0
        while (!(topAtX(d, right, 1.0) > floor + 0.5)) right -= 0.25
        assertTrue(left - right >= 6.5, "well width ${left - right}")
    }

    /** Both ramps lead off the well floor down to the water edge in steps a vehicle (up step 1.5) and a player (0.6) climb. */
    @Test
    fun theRampsAreTheWayOnAndOff() {
        val d = deck()
        val floor = d.deckHeight
        for ((from, step) in listOf(8.75 to 0.25, -6.25 to -0.25)) {
            var prev = floor
            var z = from
            var last = floor
            while (z in d.minZ..d.maxZ - 0.01) {
                val t = topAtX(d, 0.0, z)
                if (t.isNaN()) break
                assertTrue(abs(t - prev) <= 0.3 + 1e-9, "step ${t - prev} at z=$z")
                assertTrue(t <= prev + 0.15, "ramp rises $t over $prev at z=$z")   // the slab has a 1/8 block ripple at the stern
                prev = t
                last = t
                z += step
            }
            assertTrue(floor - last >= 0.6, "ramp tip only ${floor - last} below the floor (z from $from)")
            assertTrue(last > 0.8, "ramp tip at $last: below the skirt")
        }
    }

    /** A vehicle lowered onto the well rests exactly on the floor (at any heading) and is stopped by the side wall. */
    @Test
    fun aVehicleDrivesOnAndIsStoppedByTheWall() {
        val d = deck()
        val pose = DeckPose(120.0, 63.0, -40.0, 37f)
        val half = 1.1
        fun body(lx: Double, lz: Double, y: Double): AABB {
            // a 2.2 x 2.4 x 4 vehicle footprint in the craft's frame, sampled as its world AABB
            val xs = doubleArrayOf(lx - half, lx + half); val zs = doubleArrayOf(lz - 2.0, lz + 2.0)
            val wx = ArrayList<Double>(); val wz = ArrayList<Double>()
            for (x in xs) for (z in zs) { wx.add(pose.worldX(x, z)); wz.add(pose.worldZ(x, z)) }
            return AABB(wx.min(), pose.y + y, wz.min(), wx.max(), pose.y + y + 2.4, wz.max())
        }
        val rest = body(0.0, 2.0, d.deckHeight + 1.0)
        val boxes = ArrayList<AABB>()
        DeckCollisions.rasterize(d, pose, rest.expandTowards(0.0, -3.0, 0.0), boxes)
        val allowed = Shapes.collide(Direction.Axis.Y, rest, boxes.map { Shapes.create(it) }, -3.0)
        assertEquals(-1.0, allowed, 1e-6, "falls exactly onto the floor")
        // at yaw 0 the wall is a plain +X wall: a vehicle pushed sideways stops at the well edge
        val flat = DeckPose(0.0, 63.0, 0.0, 0f)
        val car = AABB(0.5, 63.0 + d.deckHeight, 0.0, 2.7, 63.0 + d.deckHeight + 2.4, 4.0)
        val wall = ArrayList<AABB>()
        DeckCollisions.rasterize(d, flat, car.expandTowards(3.0, 0.0, 0.0), wall)
        val moved = Shapes.collide(Direction.Axis.X, car, wall.map { Shapes.create(it) }, 3.0)
        assertTrue(moved in 0.7..1.1, "stopped after $moved blocks by the wall (well edge x 3.6)")
    }

    /** The deck frame carries a rider through any translation and turn: its position and heading in the craft never change. */
    @Test
    fun aRiderKeepsItsPlaceThroughTranslationAndTurn() {
        val d = deck()
        val random = Random(11)
        var pose = DeckPose(500.0, 63.0, -200.0, 10f)
        // a vehicle parked at (1.4, floor, 3.5) heading 30 deg off the craft's axis
        var lx = 1.4; var lz = 3.5
        var wx = pose.worldX(lx, lz); var wz = pose.worldZ(lx, lz)
        var yaw = pose.yaw + 30f
        repeat(500) {
            val next = DeckPose(pose.x + random.nextDouble(-0.6, 0.6), pose.y, pose.z + random.nextDouble(-0.6, 0.6),
                pose.yaw + random.nextFloat() * 2.4f - 1.2f)
            // DeckCarry.move: the old local point at the new pose, the yaw change added to the rider
            val ox = pose.localX(wx, wz); val oz = pose.localZ(wx, wz)
            wx = next.worldX(ox, oz); wz = next.worldZ(ox, oz)
            yaw += next.yaw - pose.yaw
            pose = next
            assertEquals(lx, pose.localX(wx, wz), 1e-6, "x in the craft's frame")
            assertEquals(lz, pose.localZ(wx, wz), 1e-6, "z in the craft's frame")
            assertEquals(30f, yaw - pose.yaw, 1e-3f, "heading in the craft's frame")
            assertEquals(d.deckHeight, d.topAt(pose.localX(wx, wz), pose.localZ(wx, wz)), 1e-6, "still on the well floor")
        }
        assertTrue(abs(pose.yaw - 10f) > 0f)
    }

    /**
     * Every seat's DismountInfo (VehicleEntity.getDismountLocationForIndex: the Position through the Vehicle transform, no
     * velocity offset) lands on the cargo-well floor within 2 blocks of the seat, never in a wall or on a ramp edge, also when the
     * craft has moved and turned. The first version put every dismount 1 block behind the stern: a rider in the bow cab was thrown
     * 23 blocks (the server's "moved too quickly").
     */
    @Test
    fun everySeatDismountsOntoTheFloorBesideIt() {
        val d = deck()
        val vehicle = File(System.getProperty("bvp.root") ?: "../bvp",
            "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/lcac.json")
        val seats = vehicle.reader().use { JsonParser.parseReader(it).asJsonObject }.getAsJsonArray("Seats")
        assertEquals(11, seats.size())
        val floor = d.deckHeight
        val random = Random(5)
        for ((n, e) in seats.withIndex()) {
            val seat = e.asJsonObject
            val info = seat.getAsJsonObject("DismountInfo")
            assertEquals("Vehicle", info.get("Transform").asString, "seat $n frame")
            val s = seat.getAsJsonArray("Position").map { it.asDouble }
            val p = info.getAsJsonArray("Position").map { it.asDouble }
            val horizontal = Math.hypot(p[0] - s[0], p[2] - s[2])
            assertTrue(horizontal <= 2.0, "seat $n dismount ${"%.2f".format(horizontal)} blocks from the seat")
            assertTrue(abs(p[1] - s[1]) <= 0.6, "seat $n dismount height")
            // on the walkable floor: the column is the well floor, its neighbours within half a block too, and the feet are over it
            for (dx in doubleArrayOf(-0.5, 0.0, 0.5)) for (dz in doubleArrayOf(-0.5, 0.0, 0.5)) {
                assertEquals(floor, d.topAt(p[0] + dx, p[2] + dz), 1e-6, "seat $n dismount neighbourhood ($dx, $dz)")
            }
            assertTrue(p[1] >= floor && p[1] <= floor + 0.5, "seat $n feet ${p[1]} over floor $floor")
            // the craft moved and turned: world seat and world dismount stay within 2 blocks, the dismount is still on the floor column
            repeat(50) {
                val pose = DeckPose(random.nextDouble(-900.0, 900.0), 63.0, random.nextDouble(-900.0, 900.0),
                    random.nextFloat() * 360f - 180f)
                val sx = pose.worldX(s[0], s[2]); val sz = pose.worldZ(s[0], s[2])
                val dxw = pose.worldX(p[0], p[2]); val dzw = pose.worldZ(p[0], p[2])
                assertTrue(Math.hypot(dxw - sx, dzw - sz) <= 2.0, "seat $n world distance at yaw ${pose.yaw}")
                assertEquals(floor, d.topAt(pose.localX(dxw, dzw), pose.localZ(dxw, dzw)), 1e-6, "seat $n world dismount column")
            }
        }
    }}
