package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleTransformSnapshot
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class VehicleTurretAlignmentTest {
    @Test fun `mount frame solution reproduces physical barrel direction on tilted hulls`() {
        for (roll in listOf(-25.0, 0.0, 25.0)) for (pitch in listOf(-18.0, 0.0, 18.0)) {
            val base = Matrix4d().translate(12500.0, 64.0, -25000.0)
                .rotateY(1.2).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll))
            val frame = VehicleTransformSnapshot("TurretBase", 1, 1, base)
            for (yaw in listOf(-179.0, -45.0, 0.0, 60.0, 179.0)) {
                val actual = VehicleTransformSnapshot("Barrel", 1, 1,
                    Matrix4d(base).rotateY(Math.toRadians(yaw)).rotateX(Math.toRadians(-8.0)))
                val desired = actual.localDirectionToWorld(Vec3(0.0, 0.0, 1.0))
                val local = frame.worldDirectionToLocal(desired)
                val target = VehicleAimMath.directionAngles(local.x, local.y, local.z)
                assertEquals(yaw, target.yaw.toDouble(), 1e-4)
                assertEquals(-8.0, target.pitch.toDouble(), 1e-4)
                val solved = VehicleTransformSnapshot("Barrel", 1, 1,
                    Matrix4d(base).rotateY(Math.toRadians(target.yaw.toDouble()))
                        .rotateX(Math.toRadians(target.pitch.toDouble())))
                    .localDirectionToWorld(Vec3(0.0, 0.0, 1.0))
                assertTrue(solved.distanceTo(desired) < 1e-6)
            }
        }
    }

    @Test fun `close capture is continuous and remains assisted through sustained tracking`() {
        assertEquals(1F, VehicleAimMath.closeAimMultiplier(10.0))
        assertEquals(3F, VehicleAimMath.closeAimMultiplier(0.5))
        var previous = 3F
        for (step in 0..400) {
            val gain = VehicleAimMath.closeAimMultiplier(step / 100.0)
            assertTrue(gain <= previous + 1e-6F)
            assertTrue(abs(gain - previous) < 0.016F)
            previous = gain
        }
        repeat(200) { assertEquals(2F, VehicleAimMath.closeAimMultiplier(2.0)) }
        // Same mechanical speed cap always stops at the destination, including a zero-rate drive.
        for (error in listOf(-2F, -0.01F, 0F, 0.01F, 2F)) for (rate in listOf(0F, 0.5F, 5F)) {
            val maxStep = rate * VehicleAimMath.closeAimMultiplier(abs(error.toDouble())) / 20F
            val applied = error.coerceIn(-maxStep, maxStep)
            assertTrue(abs(applied) <= abs(error))
            assertTrue(abs(applied) <= maxStep)
        }
    }
}
