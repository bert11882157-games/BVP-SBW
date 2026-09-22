package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot
import com.atsuishio.superbwarfare.client.camera.FixedWingCameraOrbit
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector4d
import kotlin.math.abs

/** No world or renderer bootstrap: production angle/admission/orbit regression checks. */
object AircraftHudPresentationTest {
    private var checks = 0

    @JvmStatic fun main(args: Array<String>) {
        check(Mth.lerp(0.5F, 179F, -179F) == 0F, "old long-arc spin reproduced")
        near(FixedWingHudMetrics.angle(179F, -179F, 0.5F)!!.toDouble(), 180.0, "short arc")
        check(FixedWingHudMetrics.angle(Float.NaN, 0F, 0.5F) == null, "invalid attitude")
        check(FixedWingHudMetrics.angle(0F, 0F, Float.NaN) == null, "invalid partial")
        for (fps in intArrayOf(30, 60, 120)) for (frame in 0..fps * 8) {
            val tickTime = frame * 20.0 / fps
            val tick = kotlin.math.floor(tickTime).toInt()
            val partial = (tickTime - tick).toFloat()
            val old = Mth.wrapDegrees(170F + tick * 2F)
            val current = Mth.wrapDegrees(172F + tick * 2F)
            val actual = FixedWingHudMetrics.angle(old, current, partial)!!
            near(Mth.wrapDegrees(actual - (170F + tickTime.toFloat() * 2F)).toDouble(),
                0.0, "${fps}fps wrapped attitude", 0.0001)
            val snapshot = VehicleFlightInstrumentSnapshot.interpolate(
                VehicleFlightInstrumentSnapshot.EMPTY.copy(bodyYaw = old, bodyPitch = old, bodyRoll = old),
                VehicleFlightInstrumentSnapshot.EMPTY.copy(bodyYaw = current, bodyPitch = current, bodyRoll = current),
                partial)
            near(snapshot.bodyYaw.toDouble(), actual.toDouble(), "accepted yaw parity")
            near(snapshot.bodyPitch.toDouble(), actual.toDouble(), "accepted pitch parity")
            near(snapshot.bodyRoll.toDouble(), actual.toDouble(), "accepted roll parity")
        }
        for ((width, height) in arrayOf(640 to 360, 960 to 540, 1920 to 1080, 3440 to 1440)) {
            check(FixedWingHudMetrics.visibleProjection(Vec3(width / 2.0, height / 2.0, 1.0), width, height),
                "finite forward projection")
            for (point in arrayOf(Vec3(1.0, 1.0, -1.0), Vec3(1.0, 1.0, 0.0),
                Vec3(1.0, 1.0, 1.0E-9), Vec3(-1.0, 1.0, 1.0),
                Vec3(width + 1.0, 1.0, 1.0), Vec3(1.0, height + 1.0, 1.0),
                Vec3(Double.NaN, 1.0, 1.0), Vec3(1.0, Double.POSITIVE_INFINITY, 1.0))) {
                check(!FixedWingHudMetrics.visibleProjection(point, width, height), "reject invalid/offscreen")
            }
        }
        for (yaw in floatArrayOf(-180F, -90F, 0F, 90F, 179F))
            for (pitch in floatArrayOf(-75F, 0F, 75F))
                for (dyaw in floatArrayOf(-85F, 0F, 85F))
                    for (dpitch in floatArrayOf(-60F, 0F, 60F))
                        for (distance in doubleArrayOf(-12.0, 12.0)) {
                            val base = Quaterniond().rotationYXZ(Math.toRadians(-yaw.toDouble()),
                                Math.toRadians(pitch.toDouble()), 0.0)
                            val target = Quaterniond().rotationYXZ(Math.toRadians(-(yaw + dyaw).toDouble()),
                                Math.toRadians((pitch + dpitch).toDouble()), 0.0)
                            val local = Vector3d(1.2, 2.3, distance)
                            val world = base.transform(Vector3d(local))
                            val actual = Vector4d(world.x + 100.0, world.y - 23.0, world.z + 400.0, 1.0)
                            check(FixedWingCameraOrbit.apply(actual, 100.0, -23.0, 400.0,
                                yaw, dyaw, dpitch), "external orbit admitted")
                            val expected = target.transform(Vector3d(local))
                            near(actual.x - 100.0, expected.x, "yaw orbit handedness")
                            near(actual.y + 23.0, expected.y, "pitch orbit handedness")
                            near(actual.z - 400.0, expected.z, "longitudinal orbit")
                            near(Vector3d(actual.x - 100.0, actual.y + 23.0, actual.z - 400.0).length(),
                                local.length(), "authored orbit radius preserved")
                        }
        val unchanged = Vector4d(1.0, 2.0, 3.0, 1.0)
        check(!FixedWingCameraOrbit.apply(unchanged, 0.0, 0.0, 0.0,
            Float.NaN, 0F, 0F), "invalid orbit rejects")
        check(unchanged == Vector4d(1.0, 2.0, 3.0, 1.0), "failed orbit does not partially mutate")
        println("PASS $checks aircraft HUD/orbit checks; wrapped 30/60/120 FPS, projection, both external sides")
    }

    private fun check(value: Boolean, label: String) {
        checks++
        if (!value) error(label)
    }

    private fun near(actual: Double, expected: Double, label: String, tolerance: Double = 1.0E-9) {
        check(actual.isFinite() && abs(actual - expected) <= tolerance, "$label: $actual != $expected")
    }
}
