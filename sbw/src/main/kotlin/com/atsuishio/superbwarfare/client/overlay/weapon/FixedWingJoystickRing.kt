package com.atsuishio.superbwarfare.client.overlay.weapon

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix4f

/** Cached subpixel geometry for the existing 13-GUI-pixel smart-joystick cue. */
internal object FixedWingJoystickRing {
    const val SEGMENTS = 96
    const val OUTER_RADIUS = 6.5f
    const val STROKE_WIDTH = 0.75f
    private const val FEATHER = 0.5f
    // The previous integer cells occupied [-6, 7], centered half a pixel past the marker.
    private const val PIXEL_CENTER = 0.5f
    private val radii = floatArrayOf(OUTER_RADIUS - STROKE_WIDTH - FEATHER,
        OUTER_RADIUS - STROKE_WIDTH, OUTER_RADIUS - FEATHER, OUTER_RADIUS)
    private val coverage = intArrayOf(0, 1, 1, 0)
    private val circle = FloatArray((SEGMENTS + 1) * 2).also { points ->
        for (i in 0 until SEGMENTS) {
            val angle = i * (2.0 * Math.PI / SEGMENTS)
            points[i * 2] = kotlin.math.cos(angle).toFloat()
            points[i * 2 + 1] = kotlin.math.sin(angle).toFloat()
        }
        points[SEGMENTS * 2] = points[0]
        points[SEGMENTS * 2 + 1] = points[1]
    }

    /** One GUI batch; no GL state changes, allocations, or trigonometry per rendered frame. */
    fun emit(pose: Matrix4f, buffer: VertexConsumer, color: Int) {
        val red = color ushr 16 and 255
        val green = color ushr 8 and 255
        val blue = color and 255
        val alpha = color ushr 24 and 255
        for (band in 0..2) {
            val inner = radii[band]
            val outer = radii[band + 1]
            val innerAlpha = alpha * coverage[band]
            val outerAlpha = alpha * coverage[band + 1]
            for (i in 0 until SEGMENTS) {
                val a = i * 2
                val b = a + 2
                vertex(buffer, pose, PIXEL_CENTER + circle[a] * inner, PIXEL_CENTER + circle[a + 1] * inner,
                    red, green, blue, innerAlpha)
                vertex(buffer, pose, PIXEL_CENTER + circle[b] * inner, PIXEL_CENTER + circle[b + 1] * inner,
                    red, green, blue, innerAlpha)
                vertex(buffer, pose, PIXEL_CENTER + circle[b] * outer, PIXEL_CENTER + circle[b + 1] * outer,
                    red, green, blue, outerAlpha)
                vertex(buffer, pose, PIXEL_CENTER + circle[a] * outer, PIXEL_CENTER + circle[a + 1] * outer,
                    red, green, blue, outerAlpha)
            }
        }
    }

    private fun vertex(buffer: VertexConsumer, pose: Matrix4f, x: Float, y: Float,
                       red: Int, green: Int, blue: Int, alpha: Int) {
        // PoseStack matrices are affine; avoid the temporary vector in the matrix overload.
        buffer.vertex((pose.m00() * x + pose.m10() * y + pose.m30()).toDouble(),
            (pose.m01() * x + pose.m11() * y + pose.m31()).toDouble(),
            (pose.m02() * x + pose.m12() * y + pose.m32()).toDouble())
            .color(red, green, blue, alpha).endVertex()
    }
}
