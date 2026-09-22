package com.atsuishio.superbwarfare.client.overlay.weapon

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix4f
import kotlin.math.hypot

/** Only the nearby arc fades in during the outer quarter of joystick travel. */
internal object FixedWingJoystickArc {
    private const val SEGMENTS = 128
    private val circle = Array(SEGMENTS + 1) { i ->
        val angle = i * 2.0 * Math.PI / SEGMENTS
        kotlin.math.cos(angle).toFloat() to kotlin.math.sin(angle).toFloat()
    }

    fun opacity(x: Float, y: Float, edgeX: Float, edgeY: Float): Float {
        val radius = hypot(x, y)
        if (radius <= 0.75f) return 0f
        val proximity = ((radius - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val alignment = (((x * edgeX + y * edgeY) / radius - 0.80f) / 0.20f).coerceIn(0f, 1f)
        return proximity * proximity * (3f - 2f * proximity) * alignment * alignment
    }

    fun emit(pose: Matrix4f, buffer: VertexConsumer, radius: Float, x: Float, y: Float) {
        if (hypot(x, y) <= 0.75f) return
        for (i in 0 until SEGMENTS) {
            val a = circle[i]
            val b = circle[i + 1]
            val alphaA = (160 * opacity(x, y, a.first, a.second)).toInt()
            val alphaB = (160 * opacity(x, y, b.first, b.second)).toInt()
            if (alphaA == 0 && alphaB == 0) continue
            buffer.vertex(pose, a.first * (radius - 1f), a.second * (radius - 1f), 0f).color(255, 173, 64, alphaA).endVertex()
            buffer.vertex(pose, b.first * (radius - 1f), b.second * (radius - 1f), 0f).color(255, 173, 64, alphaB).endVertex()
            buffer.vertex(pose, b.first * radius, b.second * radius, 0f).color(255, 173, 64, alphaB).endVertex()
            buffer.vertex(pose, a.first * radius, a.second * radius, 0f).color(255, 173, 64, alphaA).endVertex()
        }
    }
}
