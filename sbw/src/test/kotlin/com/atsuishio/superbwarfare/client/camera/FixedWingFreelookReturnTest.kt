package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import org.joml.Matrix4d
import org.joml.Vector3d
import org.joml.Vector4d
import kotlin.math.*

/** Production return-state/chase/branch replay without creating a Minecraft world. */
object FixedWingFreelookReturnTest {
    private var checks = 0
    private fun expect(value: Boolean, reason: String) { checks++; check(value) { reason } }
    private fun ray(yaw: Double, pitch: Double): Vector3d {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return Vector3d(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }
    private fun intent(yaw: Double, pitch: Double) = ray(yaw, pitch).let {
        FixedWingPilotIntent(it.x, it.y, it.z)
    }
    private fun separation(a: Vector3d, b: Vector3d) = Math.toDegrees(acos(a.dot(b).coerceIn(-1.0, 1.0)))

    @JvmStatic fun main(args: Array<String>) {
        heldPitchBand()
        releaseDuringDrag()
        val target = intent(40.0, -20.0)
        val old = FixedWingCameraMotion()
        // Exact old lifecycle: finish reset, then AFTER_LEVEL observes the retained pre-C target.
        old.reset()
        for (frame in 0..120) old.advance(frame.toLong(), frame * 16_666_667L, target,
            120.0, 30.0, 0.5, false)
        val oldOffset = old.sample()
        val oldDrift = separation(ray(120.0, 30.0), ray(120 + oldOffset.first, 30 + oldOffset.second))
        expect(oldDrift > 40.0, "reset-only release demonstrably drifts back toward the retained old world target")

        for (fps in intArrayOf(30, 60, 120)) for (firstPerson in booleanArrayOf(true, false)) {
            val player = Any(); val vehicle = Any(); val level = Any()
            var now = 0L
            val state = FixedWingCameraReturnState(); val motion = FixedWingCameraMotion()
            val branch = FixedWingThirdPersonAngles()
            branch.update(20F, 85F)
            state.hold(player, vehicle, level, 0)
            for (frame in 0..fps * 2) {
                now = frame * 1_000_000_000L / fps
                // Ordinary sampler/view rebinding resets motion but retains this same context.
                if (frame % fps == 0) motion.reset()
                state.retainContext(player, vehicle, level, 0)
                val physicalPitch = 85.0 + frame * 70.0 / fps
                val canonicalPitch = Math.toDegrees(asin(sin(Math.toRadians(physicalPitch))))
                val yaw = if (cos(Math.toRadians(physicalPitch)) < 0) 200.0 else 20.0
                branch.update(yaw.toFloat(), canonicalPitch.toFloat())
                if (!state.isBodyCentered) motion.advance(frame.toLong(), frame * 1_000_000_000L / fps,
                    target, yaw, canonicalPitch, 0.5, false)
                expect(motion.sample() == (0.0 to 0.0), "held return never rebuilds a stale chase at $fps FPS")
                val view = if (firstPerson) ray(yaw, canonicalPitch) else ray(branch.yaw().toDouble(), branch.pitch().toDouble())
                expect(view.distance(ray(20.0, physicalPitch)) < 1e-6,
                    "first/third-person release and subsequent turn/pole/inversion frames follow current body")
                expect(target == intent(40.0, -20.0), "camera lifecycle never recenters or mutates steering")
            }
            state.observeBodyFrame(fps * 2 + 1L, now)
            now += 1_000_000_000L / fps
            expect(state.rearm(fps * 2 + 2L, now), "a later accepted gesture rearms after one returned frame")
            motion.reset()
            for (frame in 0..fps) {
                motion.advance(frame.toLong(), frame * 1_000_000_000L / fps, intent(30.0, -20.0),
                    0.0, 0.0, 0.5, false)
                if (frame * 1_000_000_000L / fps < 75_000_000L)
                    expect(motion.sample() == (0.0 to 0.0), "ordinary 75ms chase delay preserved after fresh gesture")
            }
            expect(abs(motion.sample().first - 30) < 1e-6 && abs(motion.sample().second + 20) < 1e-6,
                "unrestricted chase reaches the complete aim angle after return")
            motion.advance(fps + 1L, 3_000_000_000L, intent(-80.0, 30.0), 0.0, 0.0, 0.5, false)
            expect(motion.sample() == (0.0 to 0.0), "long frame gap cannot revive old chase history")
        }
        val p = Any(); val v = Any(); val w = Any()
        for (change in 0..5) {
            val state = FixedWingCameraReturnState(); state.hold(p, v, w, 0)
            when (change) {
                0 -> state.retainContext(null, null, null, -1)
                1 -> state.retainContext(Any(), v, w, 0)
                2 -> state.retainContext(p, Any(), w, 0)
                3 -> state.retainContext(p, v, Any(), 0)
                4 -> state.retainContext(p, v, w, 1)
                else -> state.retainContext(p, null, w, -1)
            }
            expect(!state.isBodyCentered, "genuine player/vehicle/world/seat/removal cleanup")
        }

        var oldBranchError = 0.0
        for (sign in doubleArrayOf(-1.0, 1.0)) {
            val branch = FixedWingThirdPersonAngles()
            for (degree in 0..360) {
                val canonicalYaw = if (cos(Math.toRadians(degree.toDouble())) < 0) 180f else 0f
                val canonicalPitch = Math.toDegrees(asin(sin(Math.toRadians(sign * degree)))).toFloat()
                branch.update(canonicalYaw, canonicalPitch)
                for (deltaPitch in floatArrayOf(-40f, 0f, 40f)) {
                    val pitch = FixedWingCameraOrbit.pitchOffsetForBranch(canonicalYaw, branch.yaw(), deltaPitch)
                    val expected = ray(canonicalYaw + 20.0, canonicalPitch + deltaPitch.toDouble())
                    val actual = ray(branch.yaw() + 20.0, branch.pitch() + pitch.toDouble())
                    expect(actual.distance(expected) < 1e-6, "continuous branch preserves the same current camera ray")
                    oldBranchError = max(oldBranchError, separation(expected,
                        ray(branch.yaw() + 20.0, branch.pitch() + deltaPitch.toDouble())))
                    val base = Matrix4d().translation(100.0, 50.0, -70.0)
                    branch.applyOrbitRotation(base, 0.0, 0.0)
                    val position = base.transform(Vector4d(0.0, 2.0, -10.0, 1.0))
                    expect(FixedWingCameraOrbit.apply(position, 100.0, 50.0, -70.0, branch.yaw(), 20f, pitch),
                        "finite current-pivot orbit")
                    val expectedPosition = Matrix4d().translation(100.0, 50.0, -70.0)
                        .rotateY(Math.toRadians(-branch.yaw() - 20.0))
                        .rotateX(Math.toRadians(branch.pitch() + pitch.toDouble()))
                        .transform(Vector4d(0.0, 2.0, -10.0, 1.0))
                    expect(position.distance(expectedPosition) < 1e-7, "view and orbit use the same corrected pitch branch")
                }
            }
        }
        expect(oldBranchError > 79.9, "old canonical-offset plus alternate-branch composition reproduces 80-degree error")
        println("PASS $checks freelook entry/return/chase/identity/pole/orbit checks; old reset-only drift=${oldDrift}deg; old branch error=${oldBranchError}deg")
    }

    private fun releaseDuringDrag() {
        expect(!aircraftBodyReturn(null), "missing aircraft type cannot claim body return")
        for (type in VehicleType.entries) {
            expect(aircraftBodyReturn(type) == (type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER),
                "all aircraft return to current body; ground/boat restoration unchanged")
        }
        for (fps in intArrayOf(30, 60, 120)) for (view in listOf("FIRST", "REAR", "FRONT")) {
            val player = Any(); val vehicle = Any(); val level = Any()
            var now = 0L
            val state = FixedWingCameraReturnState()
            val chase = FixedWingCameraMotion()
            val retainedWorldAim = intent(80.0, -35.0)
            repeat(12) { cycle ->
                // Key release before END: there may already be a queued input callback, but
                // there has not yet been one complete returned camera frame.
                state.hold(player, vehicle, level, 0)
                chase.reset()
                val releaseFrame = cycle * (fps + 3L)
                expect(!state.rearm(releaseFrame, now), "$view release callback cannot immediately revive chase")
                state.observeBodyFrame(releaseFrame, now)
                expect(!state.rearm(releaseFrame, now), "same-frame replay cannot cross the return boundary")
                expect(state.isBodyCentered && chase.sample() == (0.0 to 0.0), "the first returned frame is body centered")
                now += 1_000_000_000L / fps
                expect(state.rearm(releaseFrame + 1, now), "$view later fresh steering needs no idle interval")
                expect(!state.rearm(releaseFrame + 1, now), "one rearm per release")
                for (frame in 0..fps) {
                    now += 1_000_000_000L / fps
                    state.retainContext(player, vehicle, level, 0)
                    chase.advance(frame.toLong(), frame * 1_000_000_000L / fps,
                        retainedWorldAim, cycle + frame * 0.6, frame * -0.2, 0.5, false)
                    expect(state.phase == "CHASE", "continued steering cannot renew a release fence")
                }
                expect(state.rearms == cycle + 1L, "bounded scalar telemetry counts actual rearm")
                expect(retainedWorldAim == intent(80.0, -35.0), "release never writes the world steering target")
            }
            state.hold(player, vehicle, level, 0)
            state.observeBodyFrame(-1, now)
            expect(!state.rearm(0, now), "an invalid frame cannot release the body fence")
            state.retainContext(player, Any(), level, 0)
            expect(!state.isBodyCentered, "context replacement revokes the fence immediately")
        }
    }

    private fun heldPitchBand() {
        // 0.5.17 rear-view entry, ticks 25998/25999: X=2 pixels, Y=0.
        // Its first zero-pitch update forced the saved 136.8381195-degree view to 90.
        val recordedEntry = 136.8381195f
        val oldPitch = recordedEntry.coerceIn(-90f, 90f)
        expect(abs((oldPitch - recordedEntry).toDouble() + 46.83811950683594) < 1e-10,
            "exact recorded artificial pitch offset reproduced")
        expect(abs(separation(ray(0.0, 136.65903), ray(0.031680003, 90.0)) - 46.659042) < 0.0001,
            "recorded 100ms camera jump is not purposeful horizontal input")

        for (mode in listOf("FIRST_PERSON", "THIRD_PERSON_BACK", "THIRD_PERSON_FRONT")) {
            for (entry in floatArrayOf(recordedEntry, -recordedEntry, 136.8f, -136.8f, 179f, -179f)) {
                val band = VehicleFreeCameraPitchBand(entry)
                for (yawDelta in floatArrayOf(0f, 0.031680003f, 2f, -2f)) {
                    val accepted = band.clamp(entry + 0f)
                    expect(accepted.toRawBits() == entry.toRawBits(), "$mode zero/horizontal-only input preserves pitch")
                    expect(accepted - entry == 0f, "$mode does not inject a legacy orbit pitch offset")
                    val yaw = 35f + yawDelta
                    // Front view uses the existing inverse input then vanilla mirroring.
                    val cameraYaw = if (mode == "THIRD_PERSON_FRONT") (yaw - 180f) + 180f else yaw
                    val cameraPitch = if (mode == "THIRD_PERSON_FRONT") -(-accepted) else accepted
                    expect(abs(cameraYaw - yaw) < 0.00002 && cameraPitch == accepted, "front inverse preserves the same branch")
                    val before = Matrix4d().rotateX(Math.toRadians(entry.toDouble()))
                        .rotateY(Math.toRadians(cameraYaw + 180.0)).transformDirection(Vector3d(0.0, 1.0, 0.0))
                    val after = Matrix4d().rotateX(Math.toRadians(cameraPitch.toDouble()))
                        .rotateY(Math.toRadians(cameraYaw + 180.0)).transformDirection(Vector3d(0.0, 1.0, 0.0))
                    expect(before.distance(after) == 0.0, "$mode keeps camera-up; no Euler canonicalization")
                }
                for (delta in floatArrayOf(-0.25f, 0.25f, -1f, 1f))
                    expect(band.clamp(entry + delta) == entry + delta, "$mode extended pitch accepts only the small requested delta")
                expect(abs(band.clamp(entry + 1000f) - band.clamp(entry - 1000f)) == 180f,
                    "$mode retains one fixed bounded session band")
            }
        }
        for (entry in floatArrayOf(-90f, -89f, 0f, 89f, 90f)) {
            val band = VehicleFreeCameraPitchBand(entry)
            for (proposed in floatArrayOf(-180f, -95f, -90f, -89f, 0f, 89f, 90f, 95f, 180f))
                expect(band.clamp(proposed) == proposed.coerceIn(-90f, 90f), "ordinary +/-90 semantics remain exact")
        }
        val fixed = VehicleFreeCameraPitchBand(179f)
        expect(fixed.clamp(181f) == 181f && fixed.clamp(269f) == 269f && fixed.clamp(271f) == 270f,
            "crossing 180 does not recapture/recenter the pitch band during a held session")
    }
}
