package com.atsuishio.superbwarfare.client.flightdisplay

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Drawing primitives for instrument pages painted into off-screen targets (GUI projection, y down, texels). */
internal object DisplayDraw {
    enum class Align { LEFT, CENTER, RIGHT }

    val font: Font get() = Minecraft.getInstance().font

    fun text(g: GuiGraphics, text: String, x: Float, y: Float, size: Float, color: Int, align: Align) {
        val f = font
        val w = f.width(text)
        val pose = g.pose()
        pose.pushPose()
        pose.translate(x, y, 0f)
        pose.scale(size, size, 1f)
        val dx = when (align) { Align.LEFT -> 0f; Align.CENTER -> -w / 2f; Align.RIGHT -> -w.toFloat() }
        pose.translate(dx, -4f, 0f)
        g.drawString(f, text, 0, 0, color, false)
        pose.popPose()
    }

    fun outline(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, t: Int, color: Int) {
        g.fill(x0, y0, x1, y0 + t, color)
        g.fill(x0, y1 - t, x1, y1, color)
        g.fill(x0, y0, x0 + t, y1, color)
        g.fill(x1 - t, y0, x1, y1, color)
    }

    fun radialTick(g: GuiGraphics, cx: Float, cy: Float, deg: Float, r0: Float, r1: Float, w: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
        line(g, cx + sx * r0, cy + sy * r0, cx + sx * r1, cy + sy * r1, w, color)
    }

    fun line(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 1e-4f } ?: return
        val nx = -dy / len * w / 2; val ny = dx / len * w / 2
        quad(g, x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, color)
    }

    fun triangle(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        polygon(g, floatArrayOf(x0, y0, x1, y1, x2, y2), color)
    }

    fun quad(g: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        polygon(g, floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3), color)
    }

    /** Filled convex polygon (x, y pairs). */
    fun polygon(g: GuiGraphics, xy: FloatArray, color: Int) {
        val n = xy.size / 2
        if (n < 3) return
        g.flush()
        val matrix = g.pose().last().pose()
        val a = (color ushr 24 and 255) / 255f; val r = (color shr 16 and 255) / 255f
        val gg = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        for (k in 1 until n - 1) {
            buffer.vertex(matrix, xy[0], xy[1], 0f).color(r, gg, b, a).endVertex()
            buffer.vertex(matrix, xy[2 * k], xy[2 * k + 1], 0f).color(r, gg, b, a).endVertex()
            buffer.vertex(matrix, xy[2 * k + 2], xy[2 * k + 3], 0f).color(r, gg, b, a).endVertex()
        }
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.enableCull()
    }

    /** Filled disc (or ring when [inner] > 0). */
    fun disc(g: GuiGraphics, cx: Float, cy: Float, outer: Float, inner: Float, color: Int, segments: Int = 64) {
        g.flush()
        val matrix = g.pose().last().pose()
        val a = (color ushr 24 and 255) / 255f; val r = (color shr 16 and 255) / 255f
        val gg = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.disableCull()
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        for (k in 0 until segments) {
            val a0 = k * 2 * Math.PI / segments; val a1 = (k + 1) * 2 * Math.PI / segments
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            if (inner <= 0f) {
                buffer.vertex(matrix, cx, cy, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c0 * outer, cy + s0 * outer, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c1 * outer, cy + s1 * outer, 0f).color(r, gg, b, a).endVertex()
            } else {
                buffer.vertex(matrix, cx + c0 * inner, cy + s0 * inner, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c0 * outer, cy + s0 * outer, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c1 * outer, cy + s1 * outer, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c0 * inner, cy + s0 * inner, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c1 * outer, cy + s1 * outer, 0f).color(r, gg, b, a).endVertex()
                buffer.vertex(matrix, cx + c1 * inner, cy + s1 * inner, 0f).color(r, gg, b, a).endVertex()
            }
        }
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.enableCull()
    }

    /**
     * Collects coloured triangles and draws them in one call (after flushing what [g] has queued, so the order of
     * fills and shapes is kept).
     */
    class Shapes(private val g: GuiGraphics) {
        private val buffer = Tesselator.getInstance().builder
        private val matrix = g.pose().last().pose()

        init {
            g.flush()
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        }

        fun tri(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
            val a = (color ushr 24 and 255) / 255f; val r = (color shr 16 and 255) / 255f
            val gg = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
            buffer.vertex(matrix, x0, y0, 0f).color(r, gg, b, a).endVertex()
            buffer.vertex(matrix, x1, y1, 0f).color(r, gg, b, a).endVertex()
            buffer.vertex(matrix, x2, y2, 0f).color(r, gg, b, a).endVertex()
        }

        fun poly(xy: FloatArray, color: Int) {
            for (k in 1 until xy.size / 2 - 1) tri(xy[0], xy[1], xy[2 * k], xy[2 * k + 1], xy[2 * k + 2], xy[2 * k + 3], color)
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
            val dx = x1 - x0; val dy = y1 - y0
            val len = sqrt(dx * dx + dy * dy).takeIf { it > 1e-4f } ?: return
            val nx = -dy / len * w / 2; val ny = dx / len * w / 2
            poly(floatArrayOf(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny), color)
        }

        /** Tick from radius r0 to r1 at [deg] clockwise from 12 o'clock around (cx, cy). */
        fun tick(cx: Float, cy: Float, deg: Double, r0: Float, r1: Float, w: Float, color: Int) {
            val a = Math.toRadians(deg)
            val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
            line(cx + sx * r0, cy + sy * r0, cx + sx * r1, cy + sy * r1, w, color)
        }

        fun disc(cx: Float, cy: Float, outer: Float, inner: Float, color: Int, segments: Int = 64) {
            for (k in 0 until segments) {
                val a0 = k * 2 * Math.PI / segments; val a1 = (k + 1) * 2 * Math.PI / segments
                val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat(); val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
                if (inner <= 0f) tri(cx, cy, cx + c0 * outer, cy + s0 * outer, cx + c1 * outer, cy + s1 * outer, color)
                else {
                    tri(cx + c0 * inner, cy + s0 * inner, cx + c0 * outer, cy + s0 * outer, cx + c1 * outer, cy + s1 * outer, color)
                    tri(cx + c0 * inner, cy + s0 * inner, cx + c1 * outer, cy + s1 * outer, cx + c1 * inner, cy + s1 * inner, color)
                }
            }
        }

        fun draw() {
            RenderSystem.setShader { GameRenderer.getPositionColorShader() }
            RenderSystem.disableCull()
            BufferUploader.drawWithShader(buffer.end())
            RenderSystem.enableCull()
        }
    }

    inline fun shapes(g: GuiGraphics, body: Shapes.() -> Unit) {
        val s = Shapes(g)
        s.body()
        s.draw()
    }

    /** Raw GL scissor in target pixels (GuiGraphics' scissor assumes the window's GUI scale). */
    fun scissor(size: Int, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int) {
        RenderSystem.enableScissor(x0 * scale, (size - y1) * scale, (x1 - x0) * scale, (y1 - y0) * scale)
    }
}
