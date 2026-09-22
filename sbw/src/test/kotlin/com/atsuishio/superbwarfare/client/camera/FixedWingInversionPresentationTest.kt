package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath
import com.atsuishio.superbwarfare.client.renderer.FixedWingRiderPose
import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

object FixedWingInversionPresentationTest {
    private var checks = 0
    private fun expect(value: Boolean, message: String) { checks++; check(value) { message } }
    private fun rad(value: Double) = Math.toRadians(value)
    private fun angle(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    private fun body(yaw: Double, pitch: Double, roll: Double) = Matrix4d()
        .translate(1200.0, 88.0, -900.0).rotateY(rad(-yaw)).rotateX(rad(pitch)).rotateZ(rad(roll))
    private fun bank(viewYaw: Double, viewPitch: Double, yaw: Double, pitch: Double, roll: Double) =
        FixedWingCockpitCamera.rollDegrees(viewYaw.toFloat(), viewPitch.toFloat(), yaw.toFloat(),
            pitch.toFloat(), roll.toFloat()).toDouble()

    @JvmStatic fun main(args: Array<String>) {
        cockpit()
        thirdPersonPoles()
        detachedViewTransition()
        freeCameraRelease()
        riders()
        fullSphereInput()
        println("PASS $checks fixed-wing inversion camera/rider/quadrant checks")
    }

    private fun view(yaw: Double, pitch: Double) = Matrix4d()
        .rotateX(rad(pitch)).rotateY(rad(yaw + 180))

    private fun rotationStep(a: Matrix4d, b: Matrix4d): Double {
        val qa = a.getNormalizedRotation(Quaterniond()).normalize()
        val qb = b.getNormalizedRotation(Quaterniond()).normalize()
        return Math.toDegrees(2 * acos(abs(qa.dot(qb)).coerceIn(0.0, 1.0)))
    }

    private fun thirdPersonPoles() {
        // Actual 0.5.11 runtime capture, THIRD_PERSON_BACK ticks 5154 -> 5156.
        // The physical aircraft moved 1.36 degrees; dropping the canonical roll flipped the view.
        val observedBefore = Matrix4d(Matrix4f().set(floatArrayOf(-1F,0F,0F,0F,
            0F,0.001891017F,0.9999982F,0F,0F,0.9999982F,-0.001891017F,0F,0F,0F,0F,1F)))
        val observedAfter = Matrix4d(Matrix4f().set(floatArrayOf(1F,0F,0F,0F,
            0F,0.02192688F,0.99975955F,0F,0F,-0.99975955F,0.02192688F,0F,0F,0F,0F,1F)))
        expect(rotationStep(observedBefore, observedAfter) > 179.9, "recorded detached-view pole failure reproduced")
        val measured = FixedWingThirdPersonAngles()
        measured.update(0F, 89.891655F)
        val before = view(measured.yaw().toDouble(), measured.pitch().toDouble())
        measured.update(-180F, 88.74358F)
        val after = view(measured.yaw().toDouble(), measured.pitch().toDouble())
        expect(rotationStep(before, after) in 1.3..1.4, "actual runtime pole pair retains its 1.36 degree motion")
        expect(abs(measured.yaw()) < 0.001 && measured.pitch() > 90F, "extended pitch replaces canonical heading flip")

        // Render hitches are not context changes: keep the already extended branch.
        // Only the samples arrive late; physical attitude advances by one small degree.
        for (missedTicks in intArrayOf(3, 10, 20)) {
            val held = FixedWingThirdPersonAngles()
            held.update(0F, 89F)
            held.update(-180F, 89F)
            val preHitch = view(held.yaw().toDouble(), held.pitch().toDouble())
            held.update(-180F, 88F)
            val postHitch = view(held.yaw().toDouble(), held.pitch().toDouble())
            expect(rotationStep(preHitch, postHitch) in 0.99..1.01,
                "$missedTicks missed ticks retain the extended-pitch branch")
            val wronglyReset = FixedWingThirdPersonAngles()
            wronglyReset.update(-180F, 88F)
            expect(rotationStep(preHitch, view(wronglyReset.yaw().toDouble(), wronglyReset.pitch().toDouble())) > 179.9,
                "$missedTicks missed-tick reset reproduces the rejected 180-degree flip")
        }

        // Repeated complete loops, both signs: ordinary roll is not part of this camera state.
        for (sign in doubleArrayOf(-1.0, 1.0)) {
            val continuous = FixedWingThirdPersonAngles()
            var previous: Matrix4d? = null
            for (step in 0..2160) {
                val physicalPitch = sign * step
                val canonicalPitch = Math.toDegrees(asin(sin(rad(physicalPitch))))
                val canonicalYaw = if (cos(rad(physicalPitch)) < 0) -145.0 else 35.0
                expect(continuous.update(canonicalYaw.toFloat(), canonicalPitch.toFloat()), "finite full-loop view admitted")
                val current = view(continuous.yaw().toDouble(), continuous.pitch().toDouble())
                previous?.let { expect(rotationStep(it, current) < 1.01, "third-person continuous through every pitch pole") }
                val ray = Vector3d(0.0, 0.0, 1.0).rotateX(rad(physicalPitch)).rotateY(rad(-35.0))
                val cameraRay = Vector3d(0.0, 0.0, 1.0).rotateX(rad(continuous.pitch().toDouble()))
                    .rotateY(rad(-continuous.yaw().toDouble()))
                expect(ray.distance(cameraRay) < 1e-6, "branch never changes physical nose ray")
                val orbit = Matrix4d().translation(123.0, 456.0, -789.0)
                continuous.applyOrbitRotation(orbit, 12.0, -7.0)
                expect(orbit.m30() == 123.0 && orbit.m31() == 456.0 && orbit.m32() == -789.0,
                    "authored orbit pivot retained exactly")
                val expectedOrbit = Matrix4d().rotateY(rad(-continuous.yaw() + 12.0))
                    .rotateX(rad(continuous.pitch() - 7.0))
                expect(orbit.transformDirection(Vector3d(0.0, 0.0, -8.0)).distance(
                    expectedOrbit.transformDirection(Vector3d(0.0, 0.0, -8.0))) < 1e-8,
                    "orbit and view consume the same branch with unchanged free-look offsets")
                previous = current
            }
        }
        val barrelRoll = FixedWingThirdPersonAngles()
        for (roll in -1080..1080) {
            barrelRoll.update(35F, 20F)
            expect(barrelRoll.yaw() == 35F && barrelRoll.pitch() == 20F,
                "ordinary barrel roll $roll does not bank the third-person horizon")
        }
        expect(!measured.update(Float.NaN, 0F), "invalid detached camera sample resets continuity")
        measured.update(-180F, 20F)
        expect(measured.yaw() == -180F && measured.pitch() == 20F, "reset starts from current valid view")
    }

    private fun detachedViewTransition() {
        // Actual R1 stage 5: both samples say THIRD_PERSON_FRONT. The first still
        // contains legacy first-person bank AND eye translation from pre-setup state.
        val staleBank = Matrix4d(Matrix4f().set(floatArrayOf(
            -0.94831276F,0.23570833F,-0.21247329F,0F,-0.27228597F,-0.9482552F,0.16331765F,0F,
            -0.1629836F,0.21272969F,0.9634223F,0F,-0.4434814F,-3.1567945F,0.25639835F,1F)))
        val nextDetached = Matrix4d(Matrix4f().set(floatArrayOf(
            0.9755778F,0.039147425F,-0.21613726F,0F,0F,0.9839901F,0.17822322F,0F,
            0.21965389F,-0.17387062F,0.9599589F,0F,0F,0F,0F,1F)))
        expect(rotationStep(staleBank, nextDetached) in 163.0..165.0,
            "recorded same-detached-view transition witness retains the erroneous 164-degree step")
        val angles = FixedWingThirdPersonAngles()
        angles.update(-167.56306F, 9.399518F)
        val firstDetached = view(angles.yaw().toDouble(), angles.pitch().toDouble())
        angles.update(-167.3113F, 10.266285F)
        val secondDetached = view(angles.yaw().toDouble(), angles.pitch().toDouble())
        expect(rotationStep(firstDetached, secondDetached) in 0.8..1.0,
            "requested detached mode is horizon-relative on its first frame, not one frame late")
        expect(rotationStep(staleBank, firstDetached) in 163.0..165.0,
            "stale pre-setup camera branch is the unwanted first-frame bank")
        expect(staleBank.getTranslation(Vector3d()).length() > 3.0 &&
            firstDetached.getTranslation(Vector3d()).lengthSquared() == 0.0,
            "new detached frame also excludes the stale legacy eye-offset translation")
    }

    private fun freeCameraRelease() {
        for (sign in doubleArrayOf(-1.0, 1.0)) for (enteredPastPole in booleanArrayOf(false, true)) {
            val current = FixedWingThirdPersonAngles()
            // Enter before or after the singularity; aircraft keeps moving during the hold.
            val entryPitch = if (enteredPastPole) 100 else 85
            for (degree in 80..entryPitch) {
                val physical = sign * degree
                current.update((if (degree > 90) -150.0 else 30.0).toFloat(),
                    Math.toDegrees(asin(sin(rad(physical)))).toFloat())
            }
            expect(current.presentation(false) === current, "body-relative camera owns view before C press")
            val entry = view(current.yaw().toDouble(), current.pitch().toDouble())
            for (degree in entryPitch..120) {
                val physical = sign * degree
                expect(current.update((if (degree > 90) -150.0 else 30.0).toFloat(),
                    Math.toDegrees(asin(sin(rad(physical)))).toFloat()), "held camera observes actual moving body")
                expect(current.presentation(true) == null, "held world-view retains exclusive camera ownership")
            }
            val release = current.presentation(false)
            expect(release === current && abs(angle(release!!.yaw() - 30.0)) < 0.001 &&
                abs(angle(release.pitch() - sign * 120.0)) < 0.001,
                "C release resumes current extended branch rather than canonical or saved entry angles")
            val returned = view(current.yaw().toDouble(), current.pitch().toDouble())
            expect(rotationStep(entry, returned) > 19.0, "release does not restore stale pre-turn world view")
            val resetOnRelease = FixedWingThirdPersonAngles()
            resetOnRelease.update(-150F, (sign * 60).toFloat())
            expect(rotationStep(returned, view(resetOnRelease.yaw().toDouble(), resetOnRelease.pitch().toDouble())) > 179.9,
                "old freelook cache clear reproduces an inappropriate inverted return orientation")
            current.update(-150F, (sign * 59).toFloat())
            expect(rotationStep(returned, view(current.yaw().toDouble(), current.pitch().toDouble())) in 0.99..1.01,
                "post-release camera continues normally on the next sample")
        }
        for (bodyRoll in doubleArrayOf(35.0, 170.0, -170.0)) {
            val cockpitRelease = bank(-25.0, 20.0, -25.0, 20.0, bodyRoll)
            expect(abs(angle(cockpitRelease - bodyRoll)) < 0.001,
                "banked/inverted first-person return uses current body bank after offsets clear")
        }
        val invalidated = FixedWingThirdPersonAngles()
        expect(invalidated.presentation(false) == null, "uninitialized/replaced context has no return branch")
        invalidated.update(0F, 89F); invalidated.update(-180F, 89F)
        invalidated.update(Float.NaN, 0F)
        expect(invalidated.presentation(false) == null, "genuine invalidation cannot revive a held branch")
    }

    private fun cockpit() {
        val angles = doubleArrayOf(-180.0, -179.99, -135.0, -90.01, -90.0, -89.99, -30.0,
            0.0, 30.0, 89.99, 90.0, 90.01, 135.0, 179.99, 180.0)
        for (yaw in doubleArrayOf(-180.0, -35.0, 0.0, 80.0, 180.0)) for (pitch in angles) for (roll in angles) {
            val aligned = bank(yaw, pitch, yaw, pitch, roll)
            expect(abs(angle(aligned - roll)) < 0.001, "nose-aligned bank remains physical at every inversion")
            for (offset in doubleArrayOf(-80.0, -30.0, 0.0, 30.0, 80.0)) {
                val value = bank(yaw + offset, pitch, yaw, pitch, roll)
                val equivalent = bank(yaw + offset, pitch, yaw + 360, pitch + 360, roll + 360)
                expect(value.isFinite() && abs(angle(value - equivalent)) < 0.001, "bank is Euler-wrap invariant")
                val coupled = bank(yaw + offset, pitch, yaw + 180, 180 - pitch, roll + 180)
                expect(abs(angle(value - coupled)) < 0.002, "equivalent pole decomposition has identical bank")
            }
        }
        // The old 2/3 weighted scalar turns a two-degree body movement into a 121-degree camera jump.
        val oldJump = abs(angle((-179.0 * 2 / 3) - (179.0 * 2 / 3)))
        val repairedJump = abs(angle(bank(30.0, 0.0, 0.0, 0.0, -179.0) - bank(30.0, 0.0, 0.0, 0.0, 179.0)))
        expect(oldJump > 120 && repairedJump < 3, "roll-wrap rapid camera-spin regression")
        for (viewYaw in doubleArrayOf(-30.0, 0.0, 30.0)) {
            var previous: Double? = null
            for (roll in -1080..1080) {
                val current = bank(viewYaw, 0.0, 0.0, 0.0, angle(roll.toDouble()))
                previous?.let { expect(abs(angle(current - it)) < 2, "repeated full inversion never adds a camera spin") }
                previous = current
            }
        }
        expect(bank(Double.NaN, 0.0, 0.0, 0.0, 0.0) == 0.0, "invalid bank fails to finite baseline")
    }

    private fun riders() {
        for (yaw in doubleArrayOf(-179.9, -90.0, 0.0, 35.0, 179.9)) {
            for (pitch in doubleArrayOf(-180.0, -90.01, -90.0, -89.99, 0.0, 89.99, 90.0, 90.01, 180.0)) {
                for (roll in doubleArrayOf(-180.0, -179.9, -90.0, 0.0, 90.0, 179.9, 180.0)) {
                    val transform = body(yaw, pitch, roll)
                    for (seat in floatArrayOf(-90F, 0F, 35F, 180F)) {
                        val rider = FixedWingRiderPose.rotation(transform, seat)
                        val actualForward = Quaterniond(rider).transform(Vector3d(0.0, 0.0, -1.0))
                        val localForward = Vector3d(0.0, 0.0, 1.0).rotateY(rad(-seat.toDouble()))
                        val expectedForward = transform.transformDirection(localForward)
                        expect(actualForward.distance(expectedForward) < 1e-5, "fixed rider follows exact native seat direction")
                        val actualUp = Quaterniond(rider).transform(Vector3d(0.0, 1.0, 0.0))
                        val expectedUp = transform.transformDirection(Vector3d(0.0, 1.0, 0.0))
                        expect(actualUp.distance(expectedUp) < 1e-5, "fixed rider inherits body inversion once")
                    }
                }
            }
        }
        for (pole in doubleArrayOf(-90.0, 90.0)) for (alpha in doubleArrayOf(0.25, 0.75)) {
            val start = pole - 0.5; val end = pole + 0.5
            val previousForward = body(0.0, start, 0.0).transformDirection(Vector3d(0.0, 0.0, 1.0))
            val currentForward = body(0.0, end, 0.0).transformDirection(Vector3d(0.0, 0.0, 1.0))
            val previousHeading = -Math.toDegrees(atan2(previousForward.x, previousForward.z))
            val currentHeading = -Math.toDegrees(atan2(currentForward.x, currentForward.z))
            val riderHeading = previousHeading + angle(currentHeading - previousHeading) * alpha
            val frame = body(0.0, start + alpha, 0.0)
            val frameForward = frame.transformDirection(Vector3d(0.0, 0.0, 1.0))
            val frameHeading = Math.toDegrees(atan2(frameForward.x, frameForward.z))
            val old = frame.getNormalizedRotation(Quaterniond()).rotateY(rad(-frameHeading))
                .rotateY(rad(180 - riderHeading)).transform(Vector3d(0.0, 0.0, -1.0))
            expect(old.distance(frameForward) > 0.5, "old independently interpolated rider heading diverges at pole")
            val fixed = Quaterniond(FixedWingRiderPose.rotation(frame, 0F)).transform(Vector3d(0.0, 0.0, -1.0))
            expect(fixed.distance(frameForward) < 1e-5, "native quaternion eliminates pole heading cancellation")
        }
    }

    private fun fullSphereInput() {
        val projection = Matrix4f().perspective(rad(70.0).toFloat(), 16F / 9F, 0.05F, 1000F)
        val neutral = FixedWingMouseAimMath.Basis(Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0))
        val input = FixedWingPilotIntent(0.0, 0.0, 1.0)
        val expectedLocal = FixedWingMouseAimMath.rotate(input, neutral, 12.0, 9.0, 1.0, false)!!
        for (yaw in doubleArrayOf(-180.0, -35.0, 0.0, 180.0)) {
            for (pitch in doubleArrayOf(-180.0, -90.01, -90.0, -89.99, 0.0, 89.99, 90.0, 90.01, 180.0)) {
                for (roll in doubleArrayOf(-180.0, -179.99, -90.0, 0.0, 90.0, 179.99, 180.0)) {
                    val physical = body(yaw, pitch, roll)
                    val view = Matrix4f().rotationZ(rad(bank(yaw, pitch, yaw, pitch, roll)).toFloat())
                        .rotateX(rad(pitch).toFloat()).rotateY(rad(yaw + 180).toFloat())
                    val inverse = Matrix4d(view).invert()
                    val basis = FixedWingMouseAimMath.Basis(inverse.transformDirection(Vector3d(1.0, 0.0, 0.0)).normalize(),
                        inverse.transformDirection(Vector3d(0.0, 1.0, 0.0)).normalize())
                    expect(FixedWingMouseAimMath.validBasis(basis), "complete inverted view yields an orthonormal input basis")
                    val forward = physical.transformDirection(Vector3d(0.0, 0.0, 1.0))
                    val start = FixedWingPilotIntent.normalized(forward.x, forward.y, forward.z, 0)!!
                    val moved = FixedWingMouseAimMath.rotate(start, basis, 12.0, 9.0, 1.0, false)!!
                    val expected = physical.transformDirection(Vector3d(expectedLocal.directionX, expectedLocal.directionY,
                        expectedLocal.directionZ))
                    expect(Vector3d(moved.directionX, moved.directionY, moved.directionZ).distance(expected) < 1e-5,
                        "lower-right mouse movement follows physical basis through poles and inversion")
                    for (depth in doubleArrayOf(-1.0, 1.0)) for (x in doubleArrayOf(-0.4, 0.4)) {
                        val worldRay = inverse.transformDirection(Vector3d(x, -0.3, depth)).normalize()
                        val marker = FixedWingMouseAimMath.project(worldRay.x, worldRay.y, worldRay.z, view, projection, 960, 540)!!
                        val rollInput = FixedWingMouseAimMath.screenRollInput(worldRay.x, worldRay.y, worldRay.z, view, projection)!!
                        expect((marker.x - 480) * x > 0 && marker.y > 270 && rollInput * x > 0,
                            "lower quadrant retains the same sign before and beyond 90 degrees")
                    }
                }
            }
        }
    }
}
