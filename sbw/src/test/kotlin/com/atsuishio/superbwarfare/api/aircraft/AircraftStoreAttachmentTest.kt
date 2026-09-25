package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreAttachment.Anchors
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreAttachment.Face
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreAttachment.Station
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AircraftStoreAttachmentTest {
    private fun near(expected: Vec3, actual: Vec3, message: String) {
        assertTrue(expected.distanceTo(actual) < 1.0e-9, "$message: expected $expected, got $actual")
    }

    @Test
    fun sideStationsUseTheOppositeSideAnchor() {
        val anchors = Anchors(top = Vec3(0.0, 0.1, 0.3), left = Vec3(-0.05, 0.0, 0.3), right = Vec3(0.05, 0.0, 0.3))
        near(anchors.top, anchors.anchor(Face.BOTTOM), "bottom station takes the top anchor")
        // A store on a -X facing side touches it with its own right side, and vice versa.
        near(anchors.right!!, anchors.anchor(Face.LEFT), "left face takes the right anchor")
        near(anchors.left!!, anchors.anchor(Face.RIGHT), "right face takes the left anchor")
        val topOnly = Anchors(top = Vec3(0.0, 0.1, 0.0))
        near(topOnly.top, topOnly.anchor(Face.LEFT), "stores without side anchors fall back to the top anchor")
    }

    @Test
    fun rightStationsMirrorLeftStations() {
        val left = Station(Vec3(-1.6, 1.0, 1.2), Face.LEFT, setOf(2, 3))
        val right = left.mirrored()
        near(Vec3(1.6, 1.0, 1.2), right.point, "mirrored point")
        assertEquals(Face.RIGHT, right.face)
        assertEquals(setOf(2, 3), right.copies)
        assertEquals(Face.BOTTOM, Face.BOTTOM.mirrored())
    }

    @Test
    fun stationFiltersSelectCopiesAndStores() {
        val stations = listOf(
            Station(Vec3(0.0, 1.0, 0.0), Face.BOTTOM, setOf(1, 3), except = setOf("a:aim9b")),
            Station(Vec3(-0.2, 1.0, 0.0), Face.LEFT, setOf(2, 3)),
            Station(Vec3(0.2, 1.0, 0.0), Face.RIGHT, setOf(2, 3)),
            Station(Vec3(-0.2, 1.0, 0.0), Face.LEFT, setOf(1), stores = setOf("a:aim9b")),
        )
        assertEquals(listOf(stations[0]), AircraftStoreAttachment.stationsFor(stations, 1, "a:mk82"))
        assertEquals(listOf(stations[3]), AircraftStoreAttachment.stationsFor(stations, 1, "a:aim9b"))
        assertEquals(listOf(stations[1], stations[2]), AircraftStoreAttachment.stationsFor(stations, 2, "a:aim9b"))
        assertEquals(3, AircraftStoreAttachment.stationsFor(stations, 3, "a:mk82")!!.size)
        assertNull(AircraftStoreAttachment.stationsFor(stations, 4, "a:mk82"), "not enough stations falls back")
        near(Vec3(0.0, 1.0, 0.0), AircraftStoreAttachment.primary(stations, Vec3(9.0, 9.0, 9.0)), "primary station")
    }

    @Test
    fun launchPointFollowsTheModelAxisForBothForwardConventions() {
        val minusZ = Anchors(top = Vec3(0.0, 0.1, 0.3), axis = Vec3(0.0, 0.0, 0.3), modelForward = "-Z")
        val p = minusZ.placement(Vec3(-2.0, 1.0, -1.0), Face.BOTTOM)
        // "-Z" models are turned 180 degrees about Y: model (dx, dy, dz) -> hull (-dx, dy, -dz).
        near(Vec3(-2.0, 0.9, -1.0), p.launch, "-Z store launches from its body axis below the lug")
        val plusZ = Anchors(top = Vec3(0.0, 0.1, -0.4), axis = Vec3(0.0, 0.0, -0.4), modelForward = "+Z")
        near(Vec3(-2.0, 0.9, -1.0), plusZ.placement(Vec3(-2.0, 1.0, -1.0), Face.BOTTOM).launch, "+Z store")
        val legacy = Anchors(top = Vec3.ZERO, launchOffset = Vec3(0.0, -0.2, 0.5))
        near(Vec3(-2.0, 0.8, -0.5), legacy.placement(Vec3(-2.0, 1.0, -1.0), Face.BOTTOM).launch,
            "stores without an axis keep LaunchOffset")
    }

    @Test
    fun layoutPutsRackCopiesOnStationsPerPosition() {
        val left = listOf(
            Station(Vec3(-1.6, 1.0, 1.2), Face.LEFT, setOf(2)),
            Station(Vec3(-1.1, 1.0, 1.2), Face.RIGHT, setOf(2)),
        )
        val positions = listOf(Vec3(-1.4, 1.0, 1.2), Vec3(1.4, 1.0, 1.2))
        val layout = AircraftStoreAttachment.layout(positions, listOf(left, left.map { it.mirrored() }), true, false,
            Anchors(top = Vec3.ZERO), null, 2, Vec3(0.6, 0.4, 0.0))
        assertEquals(4, layout.placements.size)
        val xs = layout.placements.map { it.point.x }.sorted()
        assertEquals(listOf(-1.6, -1.1, 1.1, 1.6), xs.map { Math.round(it * 10) / 10.0 })
    }
}
