package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.pose.*
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehiclePoseCompositionTest {
    private val anchor = Vec3(10.0, 20.0, 30.0)
    private val extension = VehiclePoseSnapshot.createComponents(3f, 4f, .2, .1, .01)

    @Test fun `provider runs once after movement and unchanged samples do not republish`() {
        var calls = 0
        val provider = VehiclePoseProvider { calls++; extension }
        val first = VehiclePoseComposition.sample(VehiclePoseSnapshot.IDENTITY, provider, false, 8f, 9f, anchor, 90f)!!
        assertEquals(1, calls)
        assertEquals(anchor, first.anchor)
        assertEquals(90f, first.chassisYawDegrees)
        assertEquals(0f, first.basePitchDegrees)
        assertNull(VehiclePoseComposition.sample(first, provider, false, 8f, 9f, anchor, 90f))
        assertEquals(2, calls)
    }

    @Test fun `flight suppresses provider and retires stale composition exactly once`() {
        val provider = VehiclePoseProvider { fail("flight owns attitude") }
        val clear = VehiclePoseComposition.sample(extension, provider, true, 0f, 0f, anchor, 0f)!!
        assertTrue(clear.isIdentity())
        assertNull(VehiclePoseComposition.sample(clear, provider, true, 0f, 0f, anchor, 0f))
        assertNull(VehiclePoseComposition.sample(extension, null, false, 0f, 0f, anchor, 0f))
    }

    @Test fun `legacy base layer is opt in and cannot survive a policy change`() {
        val legacy = object : VehiclePoseProvider {
            override fun updateVehiclePose(previous: VehiclePoseSnapshot) = extension
            override fun usesLegacyBasePose() = true
        }
        val old = VehiclePoseComposition.sample(VehiclePoseSnapshot.IDENTITY, legacy, false, 8f, 9f, anchor, 0f)!!
        assertEquals(8f, old.basePitchDegrees)
        val changed = VehiclePoseComposition.sample(old, VehiclePoseProvider { it }, false, 8f, 9f, anchor, 0f)!!
        assertEquals(0f, changed.basePitchDegrees)
        assertEquals(0f, changed.baseRollDegrees)
        assertEquals(extension.pitchDegrees, changed.pitchDegrees)
    }

    @Test fun `invalid provider and chassis samples fail before publication`() {
        assertThrows(IllegalArgumentException::class.java) {
            VehiclePoseComposition.sample(extension, VehiclePoseProvider { extension.copy(groundBias = Double.NaN) }, false, 0f, 0f, anchor, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehiclePoseComposition.sample(extension, VehiclePoseProvider { extension }, false, 0f, 0f, Vec3(Double.NaN, 0.0, 0.0), 0f)
        }
    }

    @Test fun `pose transform round trips at cardinal turret headings without frame drift`() {
        for (yaw in listOf(0.0, 90.0, 180.0)) {
            val pose = extension.withBasePose(8f, -6f).withChassisSample(anchor, yaw.toFloat())
            val transform = Matrix4d().translate(anchor.x, anchor.y, anchor.z).rotateY(Math.toRadians(yaw))
            pose.applyBaseAttitude(transform)
            pose.applyExtension(transform, 1.25)
            val authored = Vector3d(.75, 1.8, -.3)
            val world = transform.transformPosition(Vector3d(authored))
            val restored = Matrix4d(transform).invert().transformPosition(world)
            assertTrue(restored.distance(authored) < 1e-10, "yaw=$yaw")
            assertEquals(pose, VehiclePoseSnapshot.decode(pose.encode()))
        }
    }
}
