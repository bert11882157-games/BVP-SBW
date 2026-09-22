package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelTouchdown
import com.atsuishio.superbwarfare.client.camera.ScreenShakeCameraEffect
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Camera
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.ViewportEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Actual presentation sizing and immutable Physics event; no server or client world. */
object VehicleWheelTouchdownPresentationTest {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun expect(value: Boolean) { checks++; check(value) }
        for (group in AircraftWheelContactGroup.entries) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
                expect(VehicleLandingImpactPresentation.wheelAmplitude(group, bad) == 0.0)
                expect(VehicleLandingImpactPresentation.wheelSmokeCount(2, bad) == 0)
            }
            var previous = 0.0
            for (speed in listOf(0.001, 0.03, 0.05, 0.15, 0.5, 2.0, Double.MAX_VALUE)) {
                val peak = VehicleLandingImpactPresentation.wheelAmplitude(group, speed) * Mth.DEG_TO_RAD * 0.1
                expect(peak.isFinite() && peak >= previous && peak <= 3.0)
                val floor = when (group) {
                    AircraftWheelContactGroup.MAIN -> 2.0
                    AircraftWheelContactGroup.NOSE -> 1.2
                    AircraftWheelContactGroup.TAIL -> 1.0
                }
                expect(peak >= floor - 1e-12)
                previous = peak
                val packet = ShakeClientMessage(VehicleLandingImpactPresentation.IMPULSE_PHASE,
                    VehicleLandingImpactPresentation.OCCUPANT_RADIUS,
                    VehicleLandingImpactPresentation.wheelAmplitude(group, speed), 10.0, 5.0, -8.0)
                // Same packet amplitude conversion, actual Forge event and production camera
                // consumer. A near-zero random axis must not erase the visible roll impulse.
                for (direction in listOf(-1.0, -0.00001, 0.0, 0.00001, 1.0)) {
                    val cameraEvent = ViewportEvent.ComputeCameraAngles(null, Camera(), 0.0, 32f, -12f, 0f)
                    val roll = ScreenShakeCameraEffect.apply(cameraEvent, packet.time,
                        packet.amplitude * Mth.DEG_TO_RAD, 1f, direction, true)
                    expect(abs(abs(roll.toDouble()) - peak) < 1e-6)
                    expect(abs(roll) >= floor.toFloat() - 1e-6f && abs(roll) <= 3f)
                    val view = PoseStack()
                    view.mulPose(Axis.ZP.rotationDegrees(roll * 0.99f))
                    expect(abs(view.last().pose().m01()) > 0.01f)
                    expect(packet.x == 10.0 && packet.y == 5.0 && packet.z == -8.0)
                }
                for (contacts in 1..32) {
                    val total = VehicleLandingImpactPresentation.wheelSmokeCount(contacts, speed)
                    expect(total in 1..32)
                    val perContact = total / contacts; val remainder = total % contacts
                    expect((0 until contacts).sumOf { perContact + if (it < remainder) 1 else 0 } == total)
                }
                for (fps in listOf(30, 60, 120)) {
                    var phase = VehicleLandingImpactPresentation.IMPULSE_PHASE
                    var previousShake = peak
                    for (frame in 1..fps * 3) {
                        phase += (0.0 - phase) * (0.05 * 20.0 / fps)
                        val shake = peak * phase * sin(0.5 * PI * phase)
                        expect(shake in 0.0..previousShake && shake <= 3.0)
                        previousShake = shake
                    }
                    expect(previousShake < 0.012)
                }
            }
        }
        val main = VehicleLandingImpactPresentation.wheelAmplitude(AircraftWheelContactGroup.MAIN, 0.1)
        expect(abs(VehicleLandingImpactPresentation.wheelAmplitude(AircraftWheelContactGroup.NOSE, 0.1) / main - 0.6) < 1e-12)
        expect(abs(VehicleLandingImpactPresentation.wheelAmplitude(AircraftWheelContactGroup.TAIL, 0.1) / main - 0.5) < 1e-12)
        for (bad in listOf(-1, 0, 33, Int.MAX_VALUE)) expect(VehicleLandingImpactPresentation.wheelSmokeCount(bad, 1.0) == 0)
        expect(abs(VehicleLandingImpactPresentation.amplitude(0.5) * Mth.DEG_TO_RAD * 0.1 - 0.65) < 1e-12)
        val points = mutableListOf(Vec3(10.0, 5.0, -8.0), Vec3(12.0, 5.0, -8.0))
        val event = AircraftWheelTouchdown(1, 30, AircraftWheelContactGroup.MAIN, points, 0.1)
        points[0] = Vec3.ZERO
        expect(event.contacts[0] == Vec3(10.0, 5.0, -8.0))
        expect(runCatching { (event.contacts as MutableList).clear() }.isFailure)
        expect(VehicleLandingImpactPresentation.wheelSmokeCount(1, 0.001) == 12)
        expect(VehicleLandingImpactPresentation.wheelSmokeCount(2, 0.001) == 24)
        expect(VehicleLandingImpactPresentation.wheelSmokeCount(32, 0.001) == 32)
        // Exact old event arithmetic controls: the shared extraction must not change normal
        // explosion/weapon presentation, mounted attenuation, radial falloff or zero strength.
        for (mounted in listOf(false, true)) for (phase in listOf(0.0, 0.2, 1.0, 3.0))
            for (amplitude in listOf(0.0, 0.01, 3.0)) for (radius in listOf(0f, 0.3f, 1f))
                for (direction in listOf(-1.0, -0.00001, 0.0, 0.00001, 1.0)) {
                    val cameraEvent = ViewportEvent.ComputeCameraAngles(null, Camera(), 0.0, 32f, -12f, 5f)
                    val gain = if (mounted) 0.1 else 1.0
                    val axis = phase * sin(0.5 * PI * phase) * amplitude * radius * direction * gain
                    val roll = phase * sin(0.5 * PI * phase) * amplitude * radius * gain
                    val positive = direction > 0.0
                    val actualRoll = ScreenShakeCameraEffect.apply(cameraEvent, phase, amplitude, radius, direction, mounted)
                    expect(cameraEvent.yaw == (32.0 + if (positive) axis else -axis).toFloat())
                    expect(cameraEvent.pitch == (-12.0 + if (positive) -axis else axis).toFloat())
                    expect(actualRoll == (5.0 + if (positive) -roll else roll).toFloat())
                    expect(cameraEvent.roll == 5f)
                }
        println("PASS $checks wheel group, packet-to-Forge-event/view application, bounded smoke, severity/decay and unchanged legacy controls")
    }
}
