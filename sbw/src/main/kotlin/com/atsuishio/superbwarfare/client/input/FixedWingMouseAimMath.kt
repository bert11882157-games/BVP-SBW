package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import org.joml.Matrix4fc
import org.joml.Matrix4d
import org.joml.Vector3d
import org.joml.Vector4d
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.exp

/** Pure displacement-to-direction and direction-to-screen math; never an aircraft solver. */
object FixedWingMouseAimMath {
    const val MAX_PIXELS_PER_SAMPLE = 256.0
    const val DEGREES_PER_PIXEL = 0.15
    const val MAX_RADIANS_PER_SAMPLE = Math.PI / 2.0

    data class Basis(val right: Vector3d, val up: Vector3d)
    data class Marker(val x: Float, val y: Float, val onScreen: Boolean)
    private data class Projection(val horizontal: Double, val vertical: Double, val onScreen: Boolean)

    fun cameraBasis(modelView: Matrix4fc): Basis {
        val inverse = Matrix4d(modelView).invert()
        return Basis(inverse.transformDirection(Vector3d(1.0, 0.0, 0.0)).normalize(),
            inverse.transformDirection(Vector3d(0.0, 1.0, 0.0)).normalize())
    }

    /** Nose-centred travel with screen horizontal, independent of aircraft bank and visual FOV. */
    fun steeringBasis(nose: Vector3d, camera: Basis): Basis? {
        if (!nose.isFinite || nose.lengthSquared() < 1e-8 || !validBasis(camera)) return null
        val forward = Vector3d(nose).normalize()
        val right = Vector3d(camera.right).fma(-camera.right.dot(forward), forward)
        if (right.lengthSquared() < 1e-8) right.set(forward).cross(camera.up)
        if (right.lengthSquared() < 1e-8) return null
        right.normalize()
        return Basis(right, Vector3d(right).cross(forward).normalize())
    }

    fun aircraftBasis(yaw: Float, pitch: Float, roll: Float): Basis {
        val attitude = com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightAttitude.quaternion(yaw, pitch, roll)
        return Basis(attitude.transform(Vector3d(-1.0, 0.0, 0.0)),
            attitude.transform(Vector3d(0.0, 1.0, 0.0)))
    }

    /** Joystick right/up/forward components, independent of the presentation camera. */
    fun localDirection(intent: FixedWingPilotIntent, frame: Basis): Vector3d {
        val ray = Vector3d(intent.directionX, intent.directionY, intent.directionZ)
        val forward = Vector3d(frame.right).cross(frame.up).negate()
        return Vector3d(ray.dot(frame.right), ray.dot(frame.up), ray.dot(forward))
    }

    fun rotate(intent: FixedWingPilotIntent, basis: Basis, dx: Double, dy: Double,
               sensitivity: Double, inverted: Boolean): FixedWingPilotIntent? {
        if (!dx.isFinite() || !dy.isFinite() || !sensitivity.isFinite() || sensitivity < 0.0 ||
            !validBasis(basis)) return null
        if (dx == 0.0 && dy == 0.0) return intent
        val length = hypot(dx, dy)
        val scale = minOf(1.0, MAX_PIXELS_PER_SAMPLE / length)
        val yaw = Math.toRadians(-dx * scale * sensitivity * DEGREES_PER_PIXEL)
        val pitch = Math.toRadians(-dy * scale * sensitivity * DEGREES_PER_PIXEL) *
            if (inverted) -1.0 else 1.0
        val axis = Vector3d(basis.up).mul(yaw).fma(pitch, basis.right)
        val angle = axis.length()
        if (!angle.isFinite()) return null
        if (angle == 0.0) return intent
        axis.div(angle)
        val result = Vector3d(intent.directionX, intent.directionY, intent.directionZ)
            .rotateAxis(minOf(angle, MAX_RADIANS_PER_SAMPLE), axis.x, axis.y, axis.z).normalize()
        return FixedWingPilotIntent.normalized(result.x, result.y, result.z, intent.manualMask)
    }

    fun validBasis(basis: Basis): Boolean = basis.right.isFinite && basis.up.isFinite &&
        abs(basis.right.lengthSquared() - 1.0) < 1.0e-4 &&
        abs(basis.up.lengthSquared() - 1.0) < 1.0e-4 && abs(basis.right.dot(basis.up)) < 1.0e-4

    /** Carry a ray between orthonormal frames without inventing a physical mouse gesture. */
    fun rebase(intent: FixedWingPilotIntent, old: Basis, next: Basis): FixedWingPilotIntent {
        val ray = Vector3d(intent.directionX, intent.directionY, intent.directionZ)
        val oldForward = Vector3d(old.right).cross(old.up).negate()
        val nextForward = Vector3d(next.right).cross(next.up).negate()
        val result = Vector3d(next.right).mul(ray.dot(old.right))
            .fma(ray.dot(old.up), next.up).fma(ray.dot(oldForward), nextForward)
        return FixedWingPilotIntent.normalized(result.x, result.y, result.z, intent.manualMask) ?: intent
    }

    fun smooth(current: FixedWingPilotIntent, target: FixedWingPilotIntent,
               seconds: Double): FixedWingPilotIntent {
        if (current.directionX == target.directionX && current.directionY == target.directionY &&
            current.directionZ == target.directionZ) return target
        val blend = 1.0 - exp(-seconds.coerceIn(0.0, 0.1) / 0.018)
        val a = Vector3d(current.directionX, current.directionY, current.directionZ)
        val b = Vector3d(target.directionX, target.directionY, target.directionZ)
        if (a.distanceSquared(b) < 1.0e-16) return target
        val axis = Vector3d(a).cross(b)
        val angle = kotlin.math.atan2(axis.length(), a.dot(b).coerceIn(-1.0, 1.0))
        if (axis.lengthSquared() < 1.0e-20) return target
        axis.normalize()
        a.rotateAxis(angle * blend, axis.x, axis.y, axis.z)
        return FixedWingPilotIntent.normalized(a.x, a.y, a.z, target.manualMask) ?: target
    }

    /** Light spring: half the remaining angular displacement returns over twenty seconds. */
    fun relax(intent: FixedWingPilotIntent, body: Basis, seconds: Double): FixedWingPilotIntent {
        val nose = Vector3d(body.right).cross(body.up).negate()
        val ray = Vector3d(intent.directionX, intent.directionY, intent.directionZ)
        val axis = Vector3d(ray).cross(nose)
        if (axis.lengthSquared() < 1.0e-20) return intent
        val angle = kotlin.math.atan2(axis.length(), ray.dot(nose).coerceIn(-1.0, 1.0))
        axis.normalize()
        val fraction = 1.0 - exp(-kotlin.math.ln(2.0) * seconds.coerceIn(0.0, 0.5) / 20.0)
        ray.rotateAxis(angle * fraction, axis.x, axis.y, axis.z)
        return FixedWingPilotIntent.normalized(ray.x, ray.y, ray.z, intent.manualMask) ?: intent
    }

    /** w=0 deliberately removes position/parallax. Off-screen targets remain edge indicators. */
    fun project(x: Double, y: Double, z: Double, modelView: Matrix4fc, projection: Matrix4fc,
                width: Int, height: Int): Marker? {
        if (width < 32 || height < 32) return null
        val direction = projectDirection(x, y, z, modelView, projection) ?: return null
        val px = (width * (0.5 + direction.horizontal * 0.5)).toFloat().coerceIn(10f, width - 11f)
        val py = (height * (0.5 + direction.vertical * 0.5)).toFloat().coerceIn(10f, height - 11f)
        return Marker(px, py, direction.onScreen)
    }

    /** Continuous horizontal displacement of the same visible marker, including edge indicators. */
    fun screenRollInput(x: Double, y: Double, z: Double,
                        modelView: Matrix4fc, projection: Matrix4fc): Float? =
        projectDirection(x, y, z, modelView, projection)?.horizontal?.coerceIn(-1.0, 1.0)?.toFloat()

    fun screenVerticalInput(x: Double, y: Double, z: Double,
                            modelView: Matrix4fc, projection: Matrix4fc): Float? =
        projectDirection(x, y, z, modelView, projection)?.vertical?.coerceIn(-1.0, 1.0)?.toFloat()

    /** Chase may center the target before the aircraft turns; retain its error from the actual nose. */
    fun screenRollError(target: FixedWingPilotIntent, noseX: Double, noseY: Double, noseZ: Double,
                        modelView: Matrix4fc, projection: Matrix4fc): Float? =
        screenAxisError(target, noseX, noseY, noseZ, modelView, projection, false)

    /** Downward target displacement from the actual nose, independent of camera chase centering. */
    fun screenVerticalError(target: FixedWingPilotIntent, noseX: Double, noseY: Double, noseZ: Double,
                            modelView: Matrix4fc, projection: Matrix4fc): Float? =
        screenAxisError(target, noseX, noseY, noseZ, modelView, projection, true)

    private fun screenAxisError(target: FixedWingPilotIntent, noseX: Double, noseY: Double, noseZ: Double,
                                modelView: Matrix4fc, projection: Matrix4fc, vertical: Boolean): Float? {
        fun coordinate(x: Double, y: Double, z: Double): Double? {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || x * x + y * y + z * z < 1e-12) return null
            val clip = Vector4d(x, y, z, 0.0).mul(modelView).mul(projection)
            if (!clip.isFinite) return null
            // Do not independently clamp front-facing rays: two offscreen cues may still differ.
            return if (clip.w > 1e-8) (if (vertical) -clip.y else clip.x) / clip.w
                else projectDirection(x, y, z, modelView, projection)?.let {
                    if (vertical) it.vertical else it.horizontal
                }
        }
        val aim = coordinate(target.directionX, target.directionY, target.directionZ) ?: return null
        val nose = coordinate(noseX, noseY, noseZ) ?: return null
        return (aim - nose).takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0)?.toFloat()
    }

    private fun projectDirection(x: Double, y: Double, z: Double,
                                 modelView: Matrix4fc, projection: Matrix4fc): Projection? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val clip = Vector4d(x, y, z, 0.0).mul(modelView).mul(projection)
        if (!clip.isFinite || x * x + y * y + z * z < 1.0e-12) return null
        val inFront = clip.w > 1.0e-8
        val divisor = max(abs(clip.w), 1.0e-8)
        var horizontal = clip.x / divisor
        var vertical = -clip.y / divisor
        val onScreen = inFront && abs(horizontal) <= 1.0 && abs(vertical) <= 1.0
        if (!onScreen) {
            if (abs(horizontal) + abs(vertical) < 1.0e-8) vertical = -1.0
            val edge = max(abs(horizontal), abs(vertical))
            horizontal /= edge
            vertical /= edge
        }
        return Projection(horizontal, vertical, onScreen)
    }
}

/** One render-sample owner; cumulative cursor input and exact lifecycle/recenter baselines. */
class FixedWingMouseAimState {
    private var frame = Long.MIN_VALUE
    private var nanos = 0L
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var basis: FixedWingMouseAimMath.Basis? = null
    private var body: FixedWingMouseAimMath.Basis? = null
    private var target: FixedWingPilotIntent? = null
    private var presented: FixedWingPilotIntent? = null
    private var centering = false
    private var gestureStart: FixedWingPilotIntent? = null
    private var gestureTarget: FixedWingPilotIntent? = null
    private var gestureVertical: FixedWingPilotIntent? = null
    private var qualificationFrame = Long.MIN_VALUE
    private var deliberateDown = 0.0
    private var deliberateUp = 0.0
    private var lowerTurnRequested = false
    private var mouseGestureFrame = Long.MIN_VALUE
    private var lastMouseGestureNanos = 0L

    /** True only for the frame that consumed accepted physical mouse displacement. */
    fun consumedMouseGesture(frameId: Long): Boolean = mouseGestureFrame == frameId

    fun sample(frameId: Long, nowNanos: Long, source: FixedWingPilotIntent,
               currentBasis: FixedWingMouseAimMath.Basis, cursorX: Double, cursorY: Double,
               sensitivity: Double, inverted: Boolean, centeringPending: Boolean,
               constrain: (FixedWingPilotIntent) -> FixedWingPilotIntent = { it }): FixedWingPilotIntent? {
        return sampleInternal(frameId, nowNanos, source, currentBasis, null, cursorX, cursorY,
            sensitivity, inverted, centeringPending, constrain)
    }

    fun sampleHeld(frameId: Long, nowNanos: Long, source: FixedWingPilotIntent,
                   currentBasis: FixedWingMouseAimMath.Basis, bodyBasis: FixedWingMouseAimMath.Basis,
                   cursorX: Double, cursorY: Double, sensitivity: Double, inverted: Boolean,
                   centeringPending: Boolean,
                   constrain: (FixedWingPilotIntent) -> FixedWingPilotIntent = { it }): FixedWingPilotIntent? =
        sampleInternal(frameId, nowNanos, source, currentBasis, bodyBasis, cursorX, cursorY,
            sensitivity, inverted, centeringPending, constrain)

    private fun sampleInternal(frameId: Long, nowNanos: Long, source: FixedWingPilotIntent,
               currentBasis: FixedWingMouseAimMath.Basis, bodyBasis: FixedWingMouseAimMath.Basis?,
               cursorX: Double, cursorY: Double, sensitivity: Double, inverted: Boolean, centeringPending: Boolean,
               constrain: (FixedWingPilotIntent) -> FixedWingPilotIntent): FixedWingPilotIntent? {
        if (!cursorX.isFinite() || !cursorY.isFinite() || !FixedWingMouseAimMath.validBasis(currentBasis)) {
            reset(); return null
        }
        if (bodyBasis != null && !FixedWingMouseAimMath.validBasis(bodyBasis)) { reset(); return null }
        if (frame == frameId) return presented
        gestureStart = null; gestureTarget = null; gestureVertical = null
        val previousBasis = basis
        val elapsed = (nowNanos - nanos) * 1.0e-9
        val baseline = target == null || previousBasis == null || !elapsed.isFinite() || elapsed < 0.0 ||
            centeringPending || centering || (bodyBasis != null && (body == null || elapsed > 0.5))
        var raw = target ?: source
        var smooth = presented ?: source
        if (baseline) {
            clearInversionRequest()
            raw = source; smooth = source
        } else {
            // Only the compatibility held-stick path follows aircraft attitude. A world
            // destination stays fixed during keyboard overrides as well as normal flight.
            if (bodyBasis != null && body != null) {
                raw = FixedWingMouseAimMath.rebase(raw, body!!, bodyBasis)
                smooth = FixedWingMouseAimMath.rebase(smooth, body!!, bodyBasis)
            }
            val dx = cursorX - mouseX
            val dy = cursorY - mouseY
            val userInput = (bodyBasis == null || source.manualMask == 0) &&
                sensitivity.isFinite() && sensitivity > 0.0 &&
                (dx != 0.0 || dy != 0.0)
            // A held joystick commands aircraft axes. Camera bank/chase must not turn a
            // vertical hand movement into lateral guidance at a dive or roll boundary.
            val inputBasis = bodyBasis ?: currentBasis
            if (bodyBasis != null && !userInput && source.manualMask == 0)
                raw = FixedWingMouseAimMath.relax(raw, bodyBasis, elapsed)
            if (userInput) {
                gestureStart = raw
                if (dy != 0.0) gestureVertical = acceptedVerticalOnly(raw, inputBasis, dx, dy, sensitivity, inverted)?.let(constrain)
            }
            val rotated = if (bodyBasis != null && source.manualMask != 0) raw else
                FixedWingMouseAimMath.rotate(raw, inputBasis, dx, dy, sensitivity, inverted)
            if (rotated != null) {
                raw = if (userInput) constrain(rotated) else rotated
                if (userInput) {
                    gestureTarget = raw
                    mouseGestureFrame = frameId
                    lastMouseGestureNanos = nowNanos
                }
            }
            // The held path follows the aircraft body, never camera chase; legacy callers retain world aim.
            smooth = FixedWingMouseAimMath.smooth(smooth, raw.copy(manualMask = source.manualMask), elapsed)
        }
        // Keyboard priority is resolved per surface on the server. Fresh world-aim gestures
        // still update the destination, camera rearm and manoeuvre metadata while keys are held.
        if (bodyBasis != null && source.manualMask != 0) clearInversionRequest()
        frame = frameId; nanos = nowNanos; mouseX = cursorX; mouseY = cursorY
        basis = currentBasis; body = bodyBasis; target = raw.copy(manualMask = source.manualMask)
        presented = smooth.copy(manualMask = source.manualMask)
        centering = centeringPending
        return presented
    }

    /** One user-qualified bit; never acquired from camera motion or smoothing. */
    fun inversionRequested(modelView: Matrix4fc, projection: Matrix4fc,
                           noseX: Double, noseY: Double, noseZ: Double): Boolean {
        if (qualificationFrame == frame) return lowerTurnRequested
        qualificationFrame = frame
        val shown = presented ?: return false
        fun down(intent: FixedWingPilotIntent): Double? {
            val aircraft = body
            if (aircraft != null) {
                val local = FixedWingMouseAimMath.localDirection(intent, aircraft)
                return (-local.y / max(abs(local.z), 1e-8) / kotlin.math.tan(Math.toRadians(35.0)))
                    .coerceIn(-1.0, 1.0)
            }
            return FixedWingMouseAimMath.screenVerticalError(
                intent, noseX, noseY, noseZ, modelView, projection)?.toDouble()
        }
        if (down(shown) == null) { clearInversionRequest(); return false }
        // Once qualified, retain the manoeuvre until the nose captures the target or
        // the pilot deliberately pulls up. Held input uses aircraft axes throughout.
        val raw = target ?: run { clearInversionRequest(); return false }
        val noseLength = kotlin.math.sqrt(noseX * noseX + noseY * noseY + noseZ * noseZ)
        if (!noseLength.isFinite() || noseLength < 1e-8) { clearInversionRequest(); return false }
        val alignment = (raw.directionX * noseX + raw.directionY * noseY + raw.directionZ * noseZ) / noseLength
        if (lowerTurnRequested && alignment >= CAPTURE_ALIGNMENT &&
            nanos - lastMouseGestureNanos >= CAPTURE_IDLE_NANOS) {
            clearInversionRequest(); return false
        }
        val remainingDown = target?.let(::down)
        if (remainingDown == null || (!lowerTurnRequested && remainingDown <= LOWER_CLEAR)) {
            clearInversionRequest(); return false
        }
        val start = gestureStart ?: return lowerTurnRequested
        val targetDown = gestureTarget?.let(::down)
        if (targetDown == null || (!lowerTurnRequested && targetDown <= LOWER_CLEAR)) {
            clearInversionRequest(); return false
        }
        val vertical = gestureVertical
        if (vertical == null) {
            if (!lowerTurnRequested) deliberateDown = 0.0
            return lowerTurnRequested
        }
        val before = down(start)
        val after = down(vertical)
        if (before == null || after == null) { clearInversionRequest(); return false }
        val acceptedDown = after - before
        if (acceptedDown > 0.0) {
            deliberateUp = 0.0
            deliberateDown = (deliberateDown + acceptedDown).coerceAtMost(DELIBERATE_DOWN)
            if (deliberateDown >= DELIBERATE_DOWN && targetDown > LOWER_ACQUIRE) lowerTurnRequested = true
        } else if (lowerTurnRequested) {
            deliberateUp = (deliberateUp - acceptedDown).coerceAtMost(DELIBERATE_DOWN)
            if (deliberateUp >= DELIBERATE_DOWN) clearInversionRequest()
        } else deliberateDown = 0.0
        return lowerTurnRequested
    }

    /** Metadata-only cancellation keeps the world target, smoothing and held controls intact. */
    fun clearInversionRequest() {
        lowerTurnRequested = false; deliberateDown = 0.0; deliberateUp = 0.0
        gestureStart = null; gestureTarget = null; gestureVertical = null
    }

    private fun acceptedVerticalOnly(start: FixedWingPilotIntent, basis: FixedWingMouseAimMath.Basis,
                                     dx: Double, dy: Double, sensitivity: Double, inverted: Boolean): FixedWingPilotIntent? {
        // Isolate the accepted pitch component of the existing combined-axis sample, including
        // its shared pixel/angular caps. This probe never replaces the actual full-input target.
        val pixels = hypot(dx, dy)
        val pixelScale = minOf(1.0, FixedWingMouseAimMath.MAX_PIXELS_PER_SAMPLE / pixels)
        val yaw = Math.toRadians(-dx * pixelScale * sensitivity * FixedWingMouseAimMath.DEGREES_PER_PIXEL)
        val pitch = Math.toRadians(-dy * pixelScale * sensitivity * FixedWingMouseAimMath.DEGREES_PER_PIXEL) *
            if (inverted) -1.0 else 1.0
        val angle = Vector3d(basis.up).mul(yaw).fma(pitch, basis.right).length()
        if (!angle.isFinite() || angle == 0.0) return null
        val angleScale = minOf(1.0, FixedWingMouseAimMath.MAX_RADIANS_PER_SAMPLE / angle)
        return FixedWingMouseAimMath.rotate(start, basis, 0.0, dy * pixelScale * angleScale, sensitivity, inverted)
    }

    fun reset() {
        mouseGestureFrame = Long.MIN_VALUE
        lastMouseGestureNanos = 0L
        frame = Long.MIN_VALUE; nanos = 0L; basis = null; body = null; target = null; presented = null
        centering = false
        qualificationFrame = Long.MIN_VALUE
        clearInversionRequest()
    }

    private companion object {
        // A roll-over lower turn needs the indicator pushed well down (60% of the way to the edge) by a
        // deliberate downward movement; ordinary pitch-down travel above that stays a plain push, no roll.
        const val DELIBERATE_DOWN = 0.25
        const val LOWER_ACQUIRE = 0.60
        const val LOWER_CLEAR = 0.35
        const val CAPTURE_ALIGNMENT = 0.9998476951563913 // cos(1 degree)
        const val CAPTURE_IDLE_NANOS = 100_000_000L
    }
}

/** Five exact physical-key slots; repeats do not retrigger Home and release ignores modifiers. */
class FixedWingHeldControls<K> {
    private val pressed = LinkedHashMap<Int, K>(5)
    private var center: K? = null
    fun press(slot: Int, key: K): Boolean {
        if (slot !in 0..4 || pressed.containsKey(slot)) return false
        pressed[slot] = key
        if (slot == 4) center = key
        return true
    }
    fun release(key: K) { pressed.entries.removeIf { it.value == key } }
    fun retain(slot: Int, expected: K) {
        if (pressed[slot] != expected) pressed.remove(slot)
        if (slot == 4 && center != null && center != expected) center = null
    }
    fun clear() { pressed.clear(); center = null }
    fun mask(): Int = (0..3).fold(0) { mask, slot ->
        if (pressed.containsKey(slot)) mask or (1 shl slot) else mask
    }
    fun takeCenter(): Boolean = (center != null).also { center = null }
}
