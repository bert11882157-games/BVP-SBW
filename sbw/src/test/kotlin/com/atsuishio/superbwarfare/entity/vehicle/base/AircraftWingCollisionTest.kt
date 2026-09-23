package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainBox
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftWingCollisionTest {
    private fun box(x1: Double, x2: Double, y1: Double = 1.0, y2: Double = 2.0) = AircraftTerrainBox().apply {
        minimum = Vec3(x1, y1, -3.0); maximum = Vec3(x2, y2, 4.0)
    }
    @Test fun wingClassificationUsesPhysicalPartsAndKeepsOverlappingRootFuselageSolid() {
        val definition = AircraftTerrainContact().apply {
            fuselage = box(-6.0, 6.0) // historical discovery envelope, not the narrow physical tube
            landingGear = box(-.5, .5, 0.0, .9)
            bodyParts = listOf(box(-.6, .6), box(-4.7, -.54), box(.54, 4.7))
        }
        for (mask in 0..3) {
            val state = AircraftCollisionSnapshot.create(definition, Matrix4d(), 0F, mask)
            assertEquals(listOf(0, 1, 2, 0), state.parts.map { it.wingSide })
            assertTrue(state.parts[0].active)
            assertEquals(mask and 1 == 0, state.parts[1].active)
            assertEquals(mask and 2 == 0, state.parts[2].active)
            assertTrue(state.parts[3].active)
            val leftHit = state.clip(Vec3(-3.0, 1.5, -5.0), Vec3(-3.0, 1.5, 5.0))
            assertEquals(mask and 1 == 0, leftHit != null)
            assertNotNull(state.clip(Vec3(0.0, 1.5, -5.0), Vec3(0.0, 1.5, 5.0)))
        }
    }
}
