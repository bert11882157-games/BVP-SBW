package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import com.atsuishio.superbwarfare.api.weapon.ShotFrameReference
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class VehicleRangeBallisticsTest {
    private fun shot(model: NominalMotionModel = NominalMotionModel.DIRECT_LINEAR_GRAVITY,
        inherited: Vec3 = Vec3.ZERO): NominalShotSnapshot {
        val origin = Vec3(2.0, 4.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val frame = ShotFrameReference("cannon", 0, 0, null, null, null, null, 1, 1L, 1L)
        val muzzle = VehicleMuzzleFrame("cannon", origin, direction, origin, direction, frame)
        return NominalShotSnapshot(UUID(0, 1), 0, 0, "cannon", ResourceLocation("superbwarfare",
            if (model == NominalMotionModel.DIRECT_LINEAR_GRAVITY) "projectile" else "cannon_shell"),
            null, muzzle, 18.0, inherited, direction.scale(18.0).add(inherited), 0.05, 140, 140,
            4096.0, if (model == NominalMotionModel.DIRECT_LINEAR_GRAVITY)
                NominalBlockCollisionModel.SBW_PROJECTILE else NominalBlockCollisionModel.STANDARD_PROJECTILE,
            model, null)
    }

    @Test fun `tilted mount family previously rejected an otherwise reachable shot`() {
        val s = shot()
        val tilt = Math.toRadians(12.0)
        val family = PitchOnlyDirectionFamily { degrees ->
            val pitch = Math.toRadians(degrees)
            Vec3(-kotlin.math.sin(pitch) * kotlin.math.sin(tilt),
                kotlin.math.sin(pitch) * kotlin.math.cos(tilt), kotlin.math.cos(pitch))
        }
        val target = s.muzzle.position.add(0.0, 0.0, 250.0)
        assertNotEquals(PitchOnlySolveStatus.SOLUTION, PitchOnlyFireControl.solveFreeAirPitchOnly(
            s, target, family, -10.0, 30.0, HasFcsGAcquisition.INSTANCE).status)
        val result = requireNotNull(VehicleRangeBallistics.solve(s, target))
        val fired = s.copy(initialMotion = result.direction.scale(s.launchSpeedBlocksPerTick))
        assertTrue(requireNotNull(VehicleRangeBallistics.atTime(fired, result.flightTicks)).distanceTo(target) < 0.001)
    }

    @Test fun `both motion models compensate moving platform and camera muzzle parallax`() {
        for (model in NominalMotionModel.entries) {
            val s = shot(model, Vec3(0.18, 0.025, 0.07))
            for (range in listOf(50.0, 250.0, 1100.0)) {
                val camera = Vec3(-1.0, 6.0, -2.0)
                val target = camera.add(0.0, 0.0, range)
                val solution = requireNotNull(VehicleRangeBallistics.solve(s, target))
                val fired = s.copy(initialMotion = NominalProjectileMotion.initialMotion(solution.direction,
                    s.launchSpeedBlocksPerTick, s.inheritedPlatformMotion))
                val point = requireNotNull(VehicleRangeBallistics.atRangePlane(fired, camera, Vec3(0.0, 0.0, 1.0), range))
                assertTrue(point.distanceTo(target) < 0.005, "$model $range $point")
            }
        }
    }

    @Test fun `actual level barrel has drop and raised zero hits indicated point`() {
        val s = shot()
        val origin = s.muzzle.position
        val actual = requireNotNull(VehicleRangeBallistics.atRangePlane(s, origin, Vec3(0.0, 0.0, 1.0), 250.0))
        assertTrue(actual.y < origin.y - 1.0)
        assertEquals(origin.z + 250.0, actual.z, 1e-6)
        assertTrue(requireNotNull(VehicleRangeBallistics.solve(s, origin.add(0.0, 0.0, 250.0))).direction.y > 0)
    }

    @Test fun `range lifetime owner limit mechanical limit and invalid values stay bounded`() {
        val s = shot()
        val target = s.muzzle.position.add(0.0, 0.0, 250.0)
        assertNull(VehicleRangeBallistics.solve(s.copy(horizonTicks = 1, configuredLifetimeTicks = 1), target))
        assertNull(VehicleRangeBallistics.solve(s.copy(ownerKinematics = NominalOwnerKinematics(
            s.muzzle.position, Vec3.ZERO, 100.0, 0.0)), target))
        assertNull(VehicleRangeBallistics.solve(s, target) { it.y <= 0.0 })
        assertNull(VehicleRangeBallistics.solve(s, target.add(0.0, 0.0, 5000.0)))
        assertNull(VehicleRangeBallistics.solve(s.copy(launchSpeedBlocksPerTick = Double.NaN), target))
    }
}
