package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3d
import org.joml.Vector4d
import kotlin.math.abs

/** Circular travel math retained for compatibility with the held-stick input path. */
object FixedWingJoystickBoundary {
    fun halfSize(width: Int, height: Int): Float = minOf(width, height) * 0.30f
    // Keep the entire 13-pixel marker inside the visible boundary.
    fun halfTravel(width: Int, height: Int): Float = (halfSize(width, height) - 7f).coerceAtLeast(1f)

    data class Axes(val horizontal: Float, val vertical: Float)

    /** Circular coordinates in the supplied steering frame. */
    fun heldAxes(target: FixedWingPilotIntent, body: FixedWingMouseAimMath.Basis,
                 width: Int, height: Int): Axes? {
        if (width < 32 || height < 32 || !FixedWingMouseAimMath.validBasis(body)) return null
        val local = FixedWingMouseAimMath.localDirection(target, body)
        val scale = height / (2.0 * halfTravel(width, height) * kotlin.math.tan(Math.toRadians(35.0)))
        val divisor = abs(local.z).coerceAtLeast(1e-8)
        val horizontal = local.x * scale / divisor
        var vertical = -local.y * scale / divisor
        if (local.z <= 0.0 && kotlin.math.hypot(horizontal, vertical) < 1e-8) vertical = -1.0
        val length = kotlin.math.hypot(horizontal, vertical).coerceAtLeast(1.0)
        return Axes((horizontal / length).toFloat(), (vertical / length).toFloat())
    }

    /** Bound fresh input to a cone around this frame's forward ray, independent of visual zoom. */
    fun constrainHeld(target: FixedWingPilotIntent, body: FixedWingMouseAimMath.Basis,
                      width: Int, height: Int): FixedWingPilotIntent {
        if (width < 32 || height < 32 || !FixedWingMouseAimMath.validBasis(body)) return target
        val local = FixedWingMouseAimMath.localDirection(target, body)
        val limit = 2.0 * halfTravel(width, height) / height * kotlin.math.tan(Math.toRadians(35.0))
        val radial = kotlin.math.hypot(local.x, local.y)
        if (local.z > 0.0 && radial <= local.z * limit) return target
        // An exact rear-facing source has no lateral direction; choose the upper edge,
        // matching heldAxes, instead of leaving an unreachable target behind the plane.
        val x = if (radial <= 1e-8) 0.0 else local.x / radial
        val y = if (radial <= 1e-8) 1.0 else local.y / radial
        val forward = Vector3d(body.right).cross(body.up).negate()
        val ray = forward.fma(x * limit, body.right).fma(y * limit, body.up).normalize()
        return FixedWingPilotIntent.normalized(ray.x, ray.y, ray.z, target.manualMask,
            target.screenRollInput, target.firstPerson, target.inversionRequested) ?: target
    }

    /** Steering scale is independent of visual FOV/zoom, like the degree-based mouse input. */
    private fun steeringProjection(projection: Matrix4fc, width: Int, height: Int): Matrix4f {
        val scale = (1.0 / kotlin.math.tan(Math.toRadians(35.0))).toFloat()
        return Matrix4f(projection).m00(scale * height / width).m11(scale)
    }

    fun axes(target: FixedWingPilotIntent, nose: Vector3d, modelView: Matrix4fc,
             projection: Matrix4fc, width: Int, height: Int): Axes? {
        if (width < 32 || height < 32) return null
        val steering = steeringProjection(projection, width, height)
        val x = FixedWingMouseAimMath.screenRollError(target, nose.x, nose.y, nose.z, modelView, steering) ?: return null
        val y = FixedWingMouseAimMath.screenVerticalError(target, nose.x, nose.y, nose.z, modelView, steering) ?: return null
        val travel = halfTravel(width, height)
        val horizontal = x * width / (2f * travel)
        val vertical = y * height / (2f * travel)
        val length = kotlin.math.hypot(horizontal, vertical).coerceAtLeast(1f)
        return Axes(horizontal / length, vertical / length)
    }

    /** Clamp only newly sampled mouse intent. Camera motion alone must not steer the aircraft. */
    fun constrain(target: FixedWingPilotIntent, nose: Vector3d, modelView: Matrix4fc,
                  projection: Matrix4fc, width: Int, height: Int): FixedWingPilotIntent {
        if (width < 32 || height < 32) return target
        val steering = steeringProjection(projection, width, height)
        val aim = Vector4d(target.directionX, target.directionY, target.directionZ, 0.0).mul(modelView).mul(steering)
        val origin = Vector4d(nose.x, nose.y, nose.z, 0.0).mul(modelView).mul(steering)
        if (!aim.isFinite || !origin.isFinite || origin.w <= 1e-8) return target
        val spanX = 2.0 * halfTravel(width, height) / width
        val spanY = 2.0 * halfTravel(width, height) / height
        val ox = origin.x / origin.w
        val oy = origin.y / origin.w
        val divisor = abs(aim.w).coerceAtLeast(1e-8)
        val dx = aim.x / divisor - ox
        val dy = aim.y / divisor - oy
        val length = kotlin.math.hypot(dx / spanX, dy / spanY)
        if (aim.w > 1e-8 && length <= 1.0) return target
        val scale = length.coerceAtLeast(1.0)
        val x = ox + dx / scale
        val y = oy + dy / scale
        val viewRay = Vector4d(x, y, -1.0, 1.0).mul(Matrix4d(steering).invert())
        val direction = Matrix4d(modelView).invert().transformDirection(Vector3d(viewRay.x, viewRay.y, viewRay.z)).normalize()
        if (!direction.isFinite) return target
        return FixedWingPilotIntent.normalized(direction.x, direction.y, direction.z, target.manualMask,
            target.screenRollInput, target.firstPerson, target.inversionRequested) ?: target
    }
}
