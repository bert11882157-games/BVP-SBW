package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimState
import org.joml.Vector3d
import kotlin.math.*

/** Production sampler -> gesture hook -> completed frame -> next-frame chase, without a world. */
object MovingFreelookReleaseTest {
    private fun ray(yaw: Double, pitch: Double): Vector3d {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return Vector3d(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    @JvmStatic fun main(args: Array<String>) {
        var cases = 0
        for (fps in intArrayOf(30, 60, 120)) for (eventHz in intArrayOf(20, fps))
            for (view in listOf("FIRST", "REAR", "FRONT")) {
                cases++
                val state = FixedWingCameraReturnState()
                val motion = FixedWingCameraMotion(); val sampler = FixedWingMouseAimState()
                val player = Any(); val vehicle = Any(); val world = Any()
                val initial = ray(-25.0, 15.0).let { FixedWingPilotIntent(it.x, it.y, it.z) }
                var x = 800.0; var y = -200.0; var previousSlot = 0
                var firstRearm = -1; var maxOffset = 0.0
                state.hold(player, vehicle, world, 0)
                sampler.reset()
                for (frame in 0..fps * 2) {
                    val now = frame * 1_000_000_000L / fps
                    val slot = frame * eventHz / fps
                    if (slot != previousSlot) { x += 3.0; y += 0.5; previousSlot = slot }
                    val yaw = 40.0 + frame * 20.0 / fps; val pitch = -5.0 + frame * 4.0 / fps
                    val basis = FixedWingMouseAimMath.Basis(
                        Vector3d(-cos(Math.toRadians(yaw)), 0.0, -sin(Math.toRadians(yaw))),
                        Vector3d(-sin(Math.toRadians(yaw)) * sin(Math.toRadians(pitch)),
                            cos(Math.toRadians(pitch)), cos(Math.toRadians(yaw)) * sin(Math.toRadians(pitch))))
                    val attitude = com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightAttitude.quaternion(
                        yaw.toFloat(), pitch.toFloat(), 0F)
                    val body = FixedWingMouseAimMath.Basis(attitude.transform(Vector3d(-1.0, 0.0, 0.0)),
                        attitude.transform(Vector3d(0.0, 1.0, 0.0)))
                    val next = sampler.sampleHeld(frame.toLong(), now, initial, basis, body, x, y, 1.0, false, false)!!
                    if (frame == 0) check(!sampler.consumedMouseGesture(0)) {
                        "release baseline discards pre-release displacement"
                    }
                    // The actual input hook runs before AFTER_LEVEL body-frame observation.
                    if (sampler.consumedMouseGesture(frame.toLong()) && state.rearm(frame.toLong(), now)) {
                        check(firstRearm == -1); firstRearm = frame; motion.reset()
                    }
                    state.retainContext(player, vehicle, world, 0)
                    if (state.isBodyCentered) state.observeBodyFrame(frame.toLong(), now)
                    else motion.advance(frame.toLong(), now, next, yaw, pitch, 0.5, false)
                    val offset = motion.sample()
                    if (firstRearm < 0) check(offset == (0.0 to 0.0)) { "no old-target chase before fresh input" }
                    maxOffset = max(maxOffset, hypot(offset.first, offset.second))
                }
                check(firstRearm in 1..ceil(fps.toDouble() / eventHz).toInt()) {
                    "fresh continuous steering must resume chase at its first post-baseline input"
                }
                check(state.rearms == 1L && !state.isBodyCentered && maxOffset > 1.0)
                check(initial == ray(-25.0, 15.0).let { FixedWingPilotIntent(it.x, it.y, it.z) })
                println("MOVING_RELEASE $view fps=$fps inputHz=$eventHz rearmFrame=$firstRearm maxOffsetDeg=$maxOffset")
            }
        frameControls()
        println("PASS $cases production continuous-steering cases; one body baseline, no idle gate or restart starvation")
    }

    private fun frameControls() {
        val state = FixedWingCameraReturnState(); val p = Any(); val v = Any(); val w = Any()
        state.hold(p, v, w, 0)
        check(!state.rearm(10, 0)) { "release-frame input cannot rearm before a completed frame" }
        state.observeBodyFrame(10, 0)
        check(!state.rearm(10, 0) && !state.rearm(9, 1)) { "same-frame and stale-frame replay rejected" }
        repeat(120) { state.observeBodyFrame(11L + it, (it + 1L) * 16_666_667) }
        check(state.isBodyCentered && state.rearms == 0L) { "idle body frames cannot revive a retained target" }
        check(state.rearm(131, 2_016_666_667)) { "a later real gesture resumes chase without a pause" }
        check(!state.rearm(131, 2_016_666_667))

        state.hold(p, v, w, 0); state.observeBodyFrame(200, 0)
        check(!state.rearm(201, 500_000_000)) { "a long unobserved stall requires a new baseline" }
        state.observeBodyFrame(201, 500_000_000)
        check(!state.rearm(202, 400_000_000)) { "clock rollback cannot accept a gesture" }
        state.observeBodyFrame(202, 400_000_000)
        check(state.rearm(203, 416_666_667)) { "clock discontinuity cannot permanently suppress chase" }
        state.hold(p, v, w, 0); state.observeBodyFrame(204, 433_333_334)
        state.retainContext(p, v, Any(), 0)
        check(!state.isBodyCentered && !state.rearm(205, 450_000_001)) { "world replacement clears ownership" }
    }
}
