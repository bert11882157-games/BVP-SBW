package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FarVehicleHandoffTest {
    private val id = UUID(0, 1)
    private fun pose(x: Double, yaw: Float = 0F): VehicleChassisPresentation {
        val point = Vec3(x, 64.0, 0.0)
        val pose = VehiclePoseSnapshot.IDENTITY.withChassisSample(point, yaw)
        return VehicleChassisPresentation(pose, point, yaw, 0.0, 0F, 0, 0, 1,
            VehicleChassisPresentation.Mode.LEGACY)
    }

    @Test fun `both handoff directions preserve motion and converge without changing authority`() {
        for (fromCopy in listOf(false, true)) {
            val timeline = FarVehicleHandoff()
            timeline.sample(id, "tank", fromCopy, 0.0, pose(0.0))
            timeline.sample(id, "tank", fromCopy, 0.5, pose(0.7))
            val authority = pose(-1.6)
            val exchanged = timeline.sample(id, "tank", !fromCopy, 1.0, authority)
            assertEquals(1.4, exchanged.anchor.x, 1e-9)
            assertEquals(-1.6, authority.anchor.x, 1e-9)
            assertEquals(exchanged.anchor, exchanged.pose.anchor)
            for (step in 1..24) {
                val tick = 1.0 + step * 0.25
                val input = pose(-1.6 + step * 0.35)
                val result = timeline.sample(id, "tank", !fromCopy, tick, input)
                assertTrue(result.anchor.x >= input.anchor.x)
                if (step == 24) assertEquals(input.anchor, result.anchor)
            }
        }
    }

    @Test fun `yaw uses shortest arc across tracking replacement`() {
        val timeline = FarVehicleHandoff()
        timeline.sample(id, "tank", false, 0.0, pose(0.0, 179F))
        val exchanged = timeline.sample(id, "tank", true, 0.1, pose(0.0, -179F))
        assertEquals(-181F, exchanged.chassisYawDegrees, 1e-5F)
    }

    @Test fun `observed ground clock overlap bridges both captured outbound gaps`() {
        val cases = listOf(
            doubleArrayOf(145.56485185578825, 146.26194086212834, 159.50796144604683),
            doubleArrayOf(157.6356408215505, 158.33276720762527, 170.6),
        )
        for (sample in cases) {
            val timeline = FarVehicleHandoff()
            val overlap = FarVehicleHandoff.ClockOverlap(
                pose(sample[2]).anchor, 0F, pose(sample[2] + 1.4).anchor,
                pose(sample[2] + 1.4).anchor)
            timeline.sample(id, "ground", false, 0.0, pose(sample[0]), overlap)
            timeline.sample(id, "ground", false, 0.5, pose(sample[1]), overlap)
            val authority = pose(sample[2])
            val result = timeline.sample(id, "ground", true, 1.0, authority)
            val expected = sample[1] + (sample[1] - sample[0])
            assertTrue(authority.anchor.x - sample[1] > 12.0)
            assertEquals(expected, result.anchor.x, 1e-9)
            assertEquals(sample[2], authority.anchor.x, 1e-9)
            assertEquals(result.anchor, result.pose.anchor)
        }
    }

    @Test fun `ground overlap correction converges at render rate without renewing its start`() {
        for (fps in listOf(30, 60, 120)) {
            val timeline = FarVehicleHandoff()
            val overlap = FarVehicleHandoff.ClockOverlap(
                pose(13.0).anchor, 0F, pose(15.0).anchor, pose(15.0).anchor)
            timeline.sample(id, "ground", false, 0.0, pose(0.0), overlap)
            timeline.sample(id, "ground", false, 0.5, pose(0.7), overlap)
            var previous = timeline.sample(id, "ground", true, 1.0, pose(13.7))
            assertEquals(1.4, previous.anchor.x, 1e-9)
            val steps = (FarVehicleHandoff.BLEND_TICKS * fps / 20).toInt()
            for (step in 1..steps) {
                val elapsed = step * 20.0 / fps
                val input = pose(13.7 + elapsed * 1.4)
                val result = timeline.sample(id, "ground", true, 1.0 + elapsed, input)
                assertTrue(result.anchor.x >= previous.anchor.x)
                assertTrue(result.anchor.x <= input.anchor.x)
                // Repeated renderer/HUD queries cannot restart the correction window.
                assertEquals(result.anchor,
                    timeline.sample(id, "ground", true, 1.0 + elapsed, input).anchor)
                previous = result
                if (step == steps) assertEquals(input.anchor, result.anchor)
            }
        }
    }

    @Test fun `overlap never increases the bound for unexplained displacement or conflicting authority`() {
        fun seed(overlap: FarVehicleHandoff.ClockOverlap): FarVehicleHandoff {
            val timeline = FarVehicleHandoff()
            timeline.sample(id, "ground", false, 0.0, pose(0.0), overlap)
            timeline.sample(id, "ground", false, 0.5, pose(0.7), overlap)
            return timeline
        }
        val valid = FarVehicleHandoff.ClockOverlap(
            pose(13.0).anchor, 0F, pose(15.0).anchor, pose(15.0).anchor)
        assertEquals(40.0, seed(valid).sample(id, "ground", true, 1.0, pose(40.0)).anchor.x)
        val mismatch = valid.copy(trackedAuthority = pose(40.0).anchor)
        assertEquals(13.7, seed(mismatch).sample(id, "ground", true, 1.0, pose(13.7)).anchor.x)
        val nonfinite = valid.copy(farAuthority = Vec3(Double.NaN, 64.0, 0.0))
        assertEquals(13.7, seed(nonfinite).sample(id, "ground", true, 1.0, pose(13.7)).anchor.x)
        val unexplained = valid.copy(farAnchor = pose(40.0).anchor,
            farAuthority = pose(42.0).anchor, trackedAuthority = pose(42.0).anchor)
        assertEquals(40.7, seed(unexplained).sample(id, "ground", true, 1.0, pose(40.7)).anchor.x)
        val stationary = FarVehicleHandoff()
        stationary.sample(id, "ground", false, 0.0, pose(0.0), valid)
        stationary.sample(id, "ground", false, 0.5, pose(0.0), valid)
        assertEquals(13.7, stationary.sample(id, "ground", true, 1.0, pose(13.7)).anchor.x)
        val noOverlap = FarVehicleHandoff()
        noOverlap.sample(id, "ground", false, 0.0, pose(0.0))
        assertEquals(13.0, noOverlap.sample(id, "ground", true, 0.5, pose(13.0)).anchor.x)
        val discontinuity = pose(4.0).copy(mode = VehicleChassisPresentation.Mode.DISCONTINUITY)
        assertEquals(4.0, seed(valid).sample(id, "ground", true, 1.0, discontinuity).anchor.x)
        assertEquals(13.7, seed(valid).sample(id, "reused-id", true, 1.0, pose(13.7)).anchor.x)
        assertEquals(13.7, seed(valid).sample(id, "ground", true, 3.0, pose(13.7)).anchor.x)
        val removed = seed(valid)
        removed.forget(id)
        assertEquals(13.7, removed.sample(id, "ground", true, 1.0, pose(13.7)).anchor.x)
    }

    @Test fun `provider clock and inbound handoff cannot use ground outbound overlap`() {
        val overlap = FarVehicleHandoff.ClockOverlap(
            pose(13.0).anchor, 0F, pose(15.0).anchor, pose(15.0).anchor)
        val provider = FarVehicleHandoff()
        provider.sample(id, "provider", false, 0.0,
            pose(0.0).copy(mode = VehicleChassisPresentation.Mode.REMOTE_INTERPOLATED), overlap)
        assertEquals(13.0, provider.sample(id, "provider", true, 0.5, pose(13.0)).anchor.x)
        val inbound = FarVehicleHandoff()
        inbound.sample(id, "ground", true, 0.0, pose(0.0), overlap)
        assertEquals(13.0, inbound.sample(id, "ground", false, 0.5, pose(13.0)).anchor.x)
    }

    @Test fun `teleports changed identities stale samples and explicit lifecycle reset snap`() {
        val timeline = FarVehicleHandoff()
        fun seed() { timeline.clear(); timeline.sample(id, "tank", false, 0.0, pose(0.0)) }
        seed()
        assertEquals(40.0, timeline.sample(id, "tank", true, 0.1, pose(40.0)).anchor.x)
        seed()
        assertEquals(4.0, timeline.sample(id, "other", true, 0.1, pose(4.0)).anchor.x)
        seed()
        assertEquals(4.0, timeline.sample(id, "tank", true, 3.0, pose(4.0)).anchor.x)
        seed()
        timeline.forget(id)
        assertEquals(4.0, timeline.sample(id, "tank", true, 0.1, pose(4.0)).anchor.x)
        seed()
        timeline.clear()
        assertEquals(0, timeline.size)
    }

    @Test fun `invalid clocks cannot poison later samples and cache remains bounded`() {
        val timeline = FarVehicleHandoff()
        timeline.sample(id, "tank", false, 0.0, pose(0.0))
        assertEquals(4.0, timeline.sample(id, "tank", true, Double.NaN, pose(4.0)).anchor.x)
        assertEquals(4.0, timeline.sample(id, "tank", true, 0.1, pose(4.0)).anchor.x)
        for (value in 1..1000) timeline.sample(UUID(1, value.toLong()), "tank", false, 1.0, pose(0.0))
        assertEquals(256, timeline.size)
    }
}
