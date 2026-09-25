package com.atsuishio.superbwarfare.client.flightdisplay

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the primary flight display into the currently bound [SIZE] x [SIZE] target (GUI projection, y down, one
 * unit per texel): attitude indicator with pitch ladder, bank scale and aircraft symbol; speed tape (km/h) on the
 * left; altitude tape (blocks/metres) and vertical speed scale on the right; heading tape below; Mach in green
 * under the attitude indicator.
 */
internal object PrimaryFlightDisplayPainter {
    const val SIZE = 512

    // Layout, texels.
    private const val ADI_L = 128; private const val ADI_R = 384
    private const val ADI_T = 70; private const val ADI_B = 390
    private const val ADI_CX = 256; private const val ADI_CY = 230
    private const val PX_PER_DEG = 6.0
    private const val SPD_L = 22; private const val SPD_R = 112
    private const val ALT_L = 400; private const val ALT_R = 470
    private const val VSI_L = 478; private const val VSI_R = 506
    private const val HDG_T = 448; private const val HDG_B = 504
    private const val SPD_PX = 2.4      // per km/h
    private const val ALT_PX = 1.6      // per metre
    private const val HDG_PX = 4.0      // per degree

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val CYAN = 0xFF2CE6FF.toInt()
    private const val GREEN = 0xFF3DFF5A.toInt()
    private const val AMBER = 0xFFFFB21C.toInt()
    private const val MAGENTA = 0xFFFF4FE0.toInt()
    private const val SKY = 0xFF1F6FD1.toInt()
    private const val SKY_DEEP = 0xFF1856A8.toInt()
    private const val GROUND = 0xFF7A4A1E.toInt()
    private const val GROUND_DEEP = 0xFF5E3915.toInt()
    private const val TAPE = 0xFF3A3F47.toInt()
    private const val BG = 0xFF06080B.toInt()

    fun paint(g: GuiGraphics, state: FlightDisplayState, scale: Int) {
        val font = Minecraft.getInstance().font
        g.fill(0, 0, SIZE, SIZE, BG)
        attitude(g, font, state, scale)
        speedTape(g, font, state, scale)
        altitudeTape(g, font, state, scale)
        verticalSpeed(g, font, state)
        headingTape(g, font, state, scale)
        machAndHeader(g, font, state)
        // Screen frame.
        outline(g, 2, 2, SIZE - 2, SIZE - 2, 2, 0xFF2A2F36.toInt())
    }

    // ------------------------------------------------------------------ attitude

    private fun attitude(g: GuiGraphics, font: Font, s: FlightDisplayState, scale: Int) {
        scissor(ADI_L, ADI_T, ADI_R, ADI_B, scale)
        val pose = g.pose()
        pose.pushPose()
        pose.translate(ADI_CX.toFloat(), ADI_CY.toFloat(), 0f)
        // Right wing down: the world, and so the horizon, turns counter-clockwise on the screen.
        pose.mulPose(Axis.ZP.rotationDegrees(-s.rollRight.toFloat()))
        val shift = (s.pitchUp * PX_PER_DEG).toFloat()
        pose.translate(0f, shift, 0f)
        g.fill(-600, -1400, 600, 0, SKY)
        g.fill(-600, -1400, 600, -160, SKY_DEEP)
        g.fill(-600, 0, 600, 1400, GROUND)
        g.fill(-600, 160, 600, 1400, GROUND_DEEP)
        g.fill(-600, -2, 600, 2, WHITE)
        // Pitch ladder, every 2.5 degrees, labelled every 10.
        var deg = -90.0
        while (deg <= 90.0) {
            if (deg != 0.0 && abs(deg - s.pitchUp) < 26.0) {
                val y = (-deg * PX_PER_DEG).roundToInt()
                val major = deg % 10.0 == 0.0
                val half = when {
                    major -> 62
                    deg % 5.0 == 0.0 -> 34
                    else -> 14
                }
                if (major && deg < 0) {
                    // Below the horizon: dashed bars.
                    var x = -half
                    while (x < half) { g.fill(x, y - 1, minOf(x + 12, half), y + 2, WHITE); x += 20 }
                } else {
                    g.fill(-half, y - 1, half, y + 2, WHITE)
                }
                if (major) {
                    val tick = if (deg > 0) 8 else -8
                    g.fill(-half, y, -half + 3, y + tick, WHITE)
                    g.fill(half - 3, y, half, y + tick, WHITE)
                    val label = abs(deg).roundToInt().toString()
                    text(g, font, label, -half - 10f, y.toFloat(), 1.6f, WHITE, Align.RIGHT)
                    text(g, font, label, half + 10f, y.toFloat(), 1.6f, WHITE, Align.LEFT)
                }
            }
            deg += 2.5
        }
        pose.popPose()

        // Bank scale: fixed ticks, sky pointer turning with the horizon.
        val radius = 142f
        for (a in intArrayOf(-60, -45, -30, -20, -10, 10, 20, 30, 45, 60)) {
            val len = if (abs(a) == 30 || abs(a) == 60) 16f else 9f
            radialTick(g, ADI_CX.toFloat(), ADI_CY.toFloat(), a.toFloat(), radius, radius + len, 3f, WHITE)
        }
        triangle(g, ADI_CX - 9f, ADI_CY - radius - 14f, ADI_CX + 9f, ADI_CY - radius - 14f,
            ADI_CX.toFloat(), ADI_CY - radius - 1f, WHITE)
        pose.pushPose()
        pose.translate(ADI_CX.toFloat(), ADI_CY.toFloat(), 0f)
        pose.mulPose(Axis.ZP.rotationDegrees(-s.rollRight.toFloat()))
        triangle(g, -9f, -radius + 15f, 9f, -radius + 15f, 0f, -radius + 1f, WHITE)
        pose.popPose()
        RenderSystem.disableScissor()

        // Aircraft symbol (fixed), outlined for contrast on sky and ground.
        symbolBar(g, ADI_CX - 112, ADI_CX - 40, ADI_CY, true)
        symbolBar(g, ADI_CX + 40, ADI_CX + 112, ADI_CY, false)
        g.fill(ADI_CX - 6, ADI_CY - 6, ADI_CX + 6, ADI_CY + 6, BLACK)
        g.fill(ADI_CX - 4, ADI_CY - 4, ADI_CX + 4, ADI_CY + 4, AMBER)
        outline(g, ADI_L, ADI_T, ADI_R, ADI_B, 2, 0xFF9AA3AD.toInt())
    }

    private fun symbolBar(g: GuiGraphics, x0: Int, x1: Int, y: Int, left: Boolean) {
        g.fill(x0 - 2, y - 5, x1 + 2, y + 5, BLACK)
        val legX = if (left) x1 - 8 else x0
        g.fill(legX - 2, y - 5, legX + 10, y + 22, BLACK)
        g.fill(x0, y - 3, x1, y + 3, AMBER)
        g.fill(legX, y - 3, legX + 8, y + 20, AMBER)
    }

    // ------------------------------------------------------------------ tapes

    private fun speedTape(g: GuiGraphics, font: Font, s: FlightDisplayState, scale: Int) {
        g.fill(SPD_L, ADI_T, SPD_R, ADI_B, TAPE)
        scissor(SPD_L, ADI_T, SPD_R, ADI_B, scale)
        val v = s.speedKmh
        val first = (floor((v - 80) / 10.0) * 10).toInt()
        var mark = maxOf(0, first)
        while (mark <= v + 80) {
            val y = (ADI_CY - (mark - v) * SPD_PX).roundToInt()
            val major = mark % 50 == 0
            g.fill(SPD_R - (if (major) 16 else 9), y - 1, SPD_R, y + 2, WHITE)
            if (major) text(g, font, mark.toString(), SPD_R - 22f, y.toFloat(), 1.7f, WHITE, Align.RIGHT)
            mark += 10
        }
        RenderSystem.disableScissor()
        readout(g, font, SPD_L - 6, SPD_R + 4, formatInt(v), true)
        text(g, font, "KM/H", (SPD_L + SPD_R) / 2f, ADI_T - 14f, 1.6f, CYAN, Align.CENTER)
        outline(g, SPD_L, ADI_T, SPD_R, ADI_B, 2, 0xFF9AA3AD.toInt())
    }

    private fun altitudeTape(g: GuiGraphics, font: Font, s: FlightDisplayState, scale: Int) {
        g.fill(ALT_L, ADI_T, ALT_R, ADI_B, TAPE)
        scissor(ALT_L, ADI_T, ALT_R, ADI_B, scale)
        val a = s.altitude
        var mark = (floor((a - 110) / 10.0) * 10).toInt()
        while (mark <= a + 110) {
            val y = (ADI_CY - (mark - a) * ALT_PX).roundToInt()
            val major = mark % 50 == 0
            g.fill(ALT_L, y - 1, ALT_L + (if (major) 16 else 9), y + 2, WHITE)
            if (major) text(g, font, mark.toString(), ALT_L + 21f, y.toFloat(), 1.5f, WHITE, Align.LEFT)
            mark += 10
        }
        RenderSystem.disableScissor()
        readout(g, font, ALT_L - 4, ALT_R + 4, formatInt(a), false)
        text(g, font, "ALT", (ALT_L + ALT_R) / 2f, ADI_T - 14f, 1.6f, CYAN, Align.CENTER)
        outline(g, ALT_L, ADI_T, ALT_R, ADI_B, 2, 0xFF9AA3AD.toInt())
    }

    private fun verticalSpeed(g: GuiGraphics, font: Font, s: FlightDisplayState) {
        val top = ADI_T + 40; val bottom = ADI_B - 40
        val half = (bottom - top) / 2
        g.fill(VSI_L, top, VSI_R, bottom, 0xFF22262C.toInt())
        // Non-linear scale: 0, 5, 10, 20, 40 m/s.
        fun offset(vs: Double): Float = (sign(vs) * sqrt(minOf(abs(vs), 40.0) / 40.0) * half).toFloat()
        for (mark in doubleArrayOf(-40.0, -20.0, -10.0, -5.0, 0.0, 5.0, 10.0, 20.0, 40.0)) {
            val y = (ADI_CY - offset(mark)).roundToInt()
            g.fill(VSI_L, y - 1, VSI_L + if (mark == 0.0) 14 else 7, y + 1, WHITE)
        }
        val y = ADI_CY - offset(s.verticalSpeed)
        line(g, VSI_R.toFloat(), ADI_CY.toFloat(), VSI_L.toFloat(), y, 3f, GREEN)
        val vs = s.verticalSpeed
        if (abs(vs) >= 0.5) {
            val label = formatInt(abs(vs))
            text(g, font, label, (VSI_L + VSI_R) / 2f, if (vs > 0) top - 12f else bottom + 12f, 1.4f, GREEN, Align.CENTER)
        }
    }

    private fun headingTape(g: GuiGraphics, font: Font, s: FlightDisplayState, scale: Int) {
        g.fill(ADI_L, HDG_T, ADI_R, HDG_B, TAPE)
        scissor(ADI_L, HDG_T, ADI_R, HDG_B, scale)
        val h = s.heading
        var mark = (floor((h - 40) / 5.0) * 5).toInt()
        while (mark <= h + 40) {
            val x = (ADI_CX + (mark - h) * HDG_PX).roundToInt()
            val norm = ((mark % 360) + 360) % 360
            val major = norm % 10 == 0
            g.fill(x - 1, HDG_T, x + 2, HDG_T + if (major) 14 else 8, WHITE)
            if (major) {
                val label = when (norm) {
                    0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"
                    else -> (norm / 10).toString().padStart(2, '0')
                }
                text(g, font, label, x.toFloat(), HDG_T + 32f, 1.6f, if (norm % 90 == 0) CYAN else WHITE, Align.CENTER)
            }
            mark += 5
        }
        RenderSystem.disableScissor()
        // Lubber line and readout.
        triangle(g, ADI_CX - 8f, HDG_T - 12f, ADI_CX + 8f, HDG_T - 12f, ADI_CX.toFloat(), HDG_T + 2f, WHITE)
        val hdg = (h.roundToInt() % 360).let { if (it == 0) 360 else it }
        // Readout box sits between the Mach line (just under the attitude indicator) and the tape.
        g.fill(ADI_CX - 30, HDG_T - 30, ADI_CX + 30, HDG_T - 10, BLACK)
        outline(g, ADI_CX - 30, HDG_T - 30, ADI_CX + 30, HDG_T - 10, 2, WHITE)
        text(g, font, hdg.toString().padStart(3, '0'), ADI_CX.toFloat(), HDG_T - 20f, 1.7f, WHITE, Align.CENTER)
        outline(g, ADI_L, HDG_T, ADI_R, HDG_B, 2, 0xFF9AA3AD.toInt())
    }

    private fun machAndHeader(g: GuiGraphics, font: Font, s: FlightDisplayState) {
        val mach = s.mach
        val machText = when {
            mach == null -> "---"
            mach < 1.0 -> "." + (mach * 1000).roundToInt().coerceIn(0, 999).toString().padStart(3, '0')
            else -> String.format("%.2f", mach)
        }
        text(g, font, machText, ADI_CX.toFloat(), ADI_B + 13f, 2.0f, GREEN, Align.CENTER)
        text(g, font, "M", ADI_CX - 40f, ADI_B + 14f, 1.4f, GREEN, Align.RIGHT)
        s.throttle?.let {
            text(g, font, "THR " + (it * 100).roundToInt().coerceIn(0, 999) + "%", SPD_L.toFloat(), 24f, 1.5f, CYAN, Align.LEFT)
        }
        s.liftG?.let {
            text(g, font, "G " + String.format("%.1f", it), ALT_R.toFloat() + 30f, 24f, 1.5f, CYAN, Align.RIGHT)
        }
    }

    // ------------------------------------------------------------------ primitives

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun text(g: GuiGraphics, font: Font, text: String, x: Float, y: Float, size: Float, color: Int, align: Align) {
        val w = font.width(text)
        val pose = g.pose()
        pose.pushPose()
        pose.translate(x, y, 0f)
        pose.scale(size, size, 1f)
        val dx = when (align) { Align.LEFT -> 0f; Align.CENTER -> -w / 2f; Align.RIGHT -> -w.toFloat() }
        pose.translate(dx, -4f, 0f)
        g.drawString(font, text, 0, 0, color, false)
        pose.popPose()
    }

    private fun readout(g: GuiGraphics, font: Font, x0: Int, x1: Int, value: String, pointRight: Boolean) {
        val y0 = ADI_CY - 20; val y1 = ADI_CY + 20
        g.fill(x0, y0, x1, y1, BLACK)
        outline(g, x0, y0, x1, y1, 2, WHITE)
        if (pointRight) triangle(g, x1.toFloat(), ADI_CY - 9f, x1.toFloat(), ADI_CY + 9f, x1 + 10f, ADI_CY.toFloat(), WHITE)
        else triangle(g, x0.toFloat(), ADI_CY + 9f, x0.toFloat(), ADI_CY - 9f, x0 - 10f, ADI_CY.toFloat(), WHITE)
        text(g, Minecraft.getInstance().font, value, (x0 + x1) / 2f, ADI_CY.toFloat(), 2.3f, WHITE, Align.CENTER)
    }

    private fun outline(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, t: Int, color: Int) {
        g.fill(x0, y0, x1, y0 + t, color)
        g.fill(x0, y1 - t, x1, y1, color)
        g.fill(x0, y0, x0 + t, y1, color)
        g.fill(x1 - t, y0, x1, y1, color)
    }

    private fun radialTick(g: GuiGraphics, cx: Float, cy: Float, deg: Float, r0: Float, r1: Float, w: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
        line(g, cx + sx * r0, cy + sy * r0, cx + sx * r1, cy + sy * r1, w, color)
    }

    private fun line(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 1e-4f } ?: return
        val nx = -dy / len * w / 2; val ny = dx / len * w / 2
        triangle(g, x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, color)
        triangle(g, x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, color)
    }

    private fun triangle(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        g.flush()
        val matrix = g.pose().last().pose()
        val a = (color ushr 24 and 255) / 255f; val r = (color shr 16 and 255) / 255f
        val gg = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        buffer.vertex(matrix, x0, y0, 0f).color(r, gg, b, a).endVertex()
        buffer.vertex(matrix, x1, y1, 0f).color(r, gg, b, a).endVertex()
        buffer.vertex(matrix, x2, y2, 0f).color(r, gg, b, a).endVertex()
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.enableCull()
    }

    /** Raw GL scissor in target pixels (GuiGraphics' scissor assumes the window's GUI scale). */
    private fun scissor(x0: Int, y0: Int, x1: Int, y1: Int, scale: Int) {
        RenderSystem.enableScissor(x0 * scale, (SIZE - y1) * scale, (x1 - x0) * scale, (y1 - y0) * scale)
    }

    private fun formatInt(v: Double): String = if (v.isFinite()) v.roundToInt().toString() else "---"
}
