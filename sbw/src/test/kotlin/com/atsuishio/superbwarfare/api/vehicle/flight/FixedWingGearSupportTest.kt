package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingGearSupportTest {
    private val floor = AABB(-100.0, -2.0, -100.0, 100.0, 0.0, 100.0)

    private fun wheel(x: Double = 0.0, y: Double = 0.25, z: Double = 0.0) =
        OBB(Vector3d(x, y, z), Vector3d(0.1, 0.25, 0.25), Quaterniond(), OBB.Part.BODY)

    @Test fun wheelTouchSupportsTaxiWithoutAddingVelocityOrFollowingTheTerrainDown() {
        for (speed in listOf(0.0, 0.4, 1.75, 4.0)) {
            val movement = Vec3(0.0, -0.01, speed)
            assertEquals(0.0, requireNotNull(FixedWingGearSupport.supportHeight(
                listOf(wheel()), listOf(floor), movement, 0.0)), 1e-8)
            assertNull(FixedWingGearSupport.supportHeight(listOf(wheel(y = 1.25)),
                listOf(floor), movement, 0.0), "no suspension snap toward distant terrain")
        }
    }

    @Test fun positiveLiftLeavesTheRunwayAndEmptySpaceDoesNotSupportWheels() {
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel()), listOf(floor),
            Vec3(0.0, 0.05, 1.0), 0.0))
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel()), emptyList(),
            Vec3(0.0, -0.05, 1.0), 0.3))
        val gap = listOf(AABB(-3.0, -1.0, -3.0, -0.2, 0.0, 3.0),
            AABB(0.2, -1.0, -3.0, 3.0, 0.0, 3.0))
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel()), gap,
            Vec3(0.0, -0.05, 0.0), 0.0), "use wheel geometry, not its aircraft-wide enclosing box")
    }

    @Test fun noseUpRotationKeepsTheMainWheelOnTheFloorAcrossAircraftLengths() {
        for (rearDistance in listOf(1.5, 4.0, 12.0)) {
            var referenceHeight = 0.0
            var previousMinimum = 0.0
            for (tenths in 1..150) {
                val rotation = Quaterniond().rotateX(Math.toRadians(-tenths / 10.0))
                val localCenter = Vector3d(0.0, 0.25, -rearDistance)
                rotation.transform(localCenter)
                localCenter.y += referenceHeight
                val gear = OBB(localCenter, Vector3d(0.1, 0.25, 0.25), rotation, OBB.Part.BODY)
                val minimum = FixedWingContactSweep.Body(gear).bounds.minY
                val allowance = (previousMinimum - minimum).coerceIn(0.0, 0.5)
                val supported = requireNotNull(FixedWingGearSupport.supportHeight(listOf(gear),
                    listOf(floor), Vec3(0.0, -0.005, 0.7), allowance))
                referenceHeight += supported
                previousMinimum = minimum + supported
                assertEquals(0.0, previousMinimum, 1e-7)
                assertTrue(supported < 0.025, "rotation must cause only a bounded geometric displacement")
            }
            assertTrue(referenceHeight > 0.3)
        }
    }

    @Test fun hardDescentIsClippedButRetainsItsOriginalImpactEnergy() {
        for (sinkPerTick in listOf(0.025, 0.1, 0.3, 0.8)) {
            val movement = Vec3(0.0, -sinkPerTick, 1.5)
            val height = requireNotNull(FixedWingGearSupport.supportHeight(listOf(wheel(y = 0.26)),
                listOf(floor), movement, 0.0))
            assertEquals(-0.01, height, 1e-8)
            val normalEnergy = sinkPerTick * sinkPerTick * 400.0
            val damage = FixedWingImpactModel.evaluate(normalEnergy, movement.lengthSqr() * 400.0, true)
            // 6 m/s damages the undercarriage; only a crash-rate sink collapses it.
            assertEquals(sinkPerTick * 20.0 >= FixedWingImpactModel.GEAR_LETHAL_SINK_SPEED, damage.destructive)
            assertEquals(sinkPerTick * 20.0 > FixedWingImpactModel.GEAR_SAFE_SINK_SPEED, damage.healthFraction > 0.0)
        }
    }

    @Test fun embeddedWheelsAndWallsCannotBecomeAnUpwardStep() {
        val wall = AABB(-1.0, -1.0, 0.5, 1.0, 3.0, 2.0)
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel()), listOf(wall),
            Vec3(0.0, -0.005, 1.0), 0.2))
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel(y = -0.25)), listOf(floor),
            Vec3(0.0, -0.005, 1.0), 0.1))
    }

    @Test fun independentWheelContactsUseTheHighestReachableSupport() {
        val gear = listOf(wheel(x = -1.0, y = 0.3), wheel(x = 1.0, y = 0.35))
        assertEquals(-0.05, requireNotNull(FixedWingGearSupport.supportHeight(gear,
            listOf(floor), Vec3(0.0, -0.2, 0.0), 0.0)), 1e-8)
    }

    @Test fun conservativeTyrePaddingCanSettleWithoutPermittingADeepExtraction() {
        assertEquals(0.005, requireNotNull(FixedWingGearSupport.supportHeight(
            listOf(wheel(y = 0.245)), listOf(floor), Vec3(0.0, 0.0, 0.0), 0.00501)), 1e-8)
        assertNull(FixedWingGearSupport.supportHeight(listOf(wheel(y = 0.23)),
            listOf(floor), Vec3(0.0, 0.0, 0.0), 0.00501))
    }

    @Test fun rotationExemptionRequiresAPreviouslyClearBodyInTheSameContext() {
        fun body(minimum: Double) = FixedWingContactSweep.Body(OBB(Vector3d(0.0, minimum + 0.1, 0.0),
            Vector3d(0.1, 0.1, 0.1), Quaterniond(), OBB.Part.BODY))
        val current = body(-0.023414)
        val movement = Vec3(0.0, 0.056720, 1.0)
        val contact = requireNotNull(current.sweep(movement, floor))
        assertFalse(FixedWingGearSupport.rotationCreatedFloorOverlap(current, body(-0.04), contact,
            movement, floor, 0.0), "wheel support cannot erase an existing fuselage scrape")
        assertFalse(FixedWingGearSupport.rotationCreatedFloorOverlap(current, null, contact,
            movement, floor, 0.0), "missing previous physical context is not clearance")
        assertTrue(FixedWingGearSupport.rotationCreatedFloorOverlap(current, body(0.001), contact,
            movement, floor, 0.0))
        assertFalse(FixedWingGearSupport.rotationCreatedFloorOverlap(current, body(0.001), contact,
            Vec3(0.0, 0.01, 1.0), floor, 0.0), "an uncleared final body is still a collision")
    }
}
