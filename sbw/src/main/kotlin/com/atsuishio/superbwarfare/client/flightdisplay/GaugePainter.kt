package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.client.flightdisplay.DisplayDraw.Align
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Round analogue instrument faces, painted into one [CELL] x [CELL] cell of the gauge atlas (GUI projection, y down,
 * cell origin at the current pose origin). Live kinds read a [FlightDisplayState]; fillers are fixed dials.
 */
internal object GaugePainter {
    const val CELL = 256
    private const val C = CELL / 2f
    private const val R = 120f          // dial radius; the bezel covers the rest

    private const val FACE = 0xFF141618.toInt()
    private const val MARK = 0xFFECECE4.toInt()
    private const val LABEL = 0xFFD8D8CC.toInt()
    private const val NEEDLE = 0xFFF4F4EC.toInt()
    private const val OUTLINE = 0xFF000000.toInt()
    private const val ORANGE = 0xFFFF8A1C.toInt()
    private const val RED = 0xFFE0342A.toInt()
    private const val GREEN = 0xFF3FB950.toInt()
    private const val YELLOW = 0xFFF2C230.toInt()
    private const val SKY = 0xFF2F7FCF.toInt()
    private const val GROUND = 0xFF7A5226.toInt()

    enum class Kind(val live: Boolean) {
        ALTIMETER(true), ATTITUDE(true), HEADING(true), THROTTLE(true),
        FILLER_FUEL(false), FILLER_OIL(false), FILLER_RPM(false), FILLER_CLOCK(false),
        FILLER_TEMP(false), FILLER_VOLTS(false), FILLER_HYDRAULIC(false), FILLER_OXYGEN(false);

        companion object {
            private val byName = values().associateBy { it.name }
            fun of(name: String?): Kind? = name?.uppercase()?.let(byName::get)
        }
    }

    fun paint(g: GuiGraphics, kind: Kind, s: FlightDisplayState) {
        DisplayDraw.shapes(g) { disc(C, C, C, 0f, 0xFF050505.toInt()); disc(C, C, R, 0f, FACE) }
        when (kind) {
            Kind.ALTIMETER -> altimeter(g, s)
            Kind.ATTITUDE -> attitude(g, s)
            Kind.HEADING -> heading(g, s)
            Kind.THROTTLE -> dial(g, "THROTTLE", "%", 0.0, 100.0, 10.0, 20.0, (s.throttle ?: 0.0) * 100.0,
                redFrom = 95.0)
            Kind.FILLER_FUEL -> fuel(g)
            Kind.FILLER_OIL -> dial(g, "OIL PRESS", "PSI", 0.0, 100.0, 5.0, 20.0, 62.0, greenFrom = 40.0, greenTo = 80.0)
            Kind.FILLER_RPM -> dial(g, "RPM", "x100", 0.0, 35.0, 1.0, 5.0, 23.5, redFrom = 31.0)
            Kind.FILLER_CLOCK -> clock(g)
            Kind.FILLER_TEMP -> dial(g, "OIL TEMP", "C", 0.0, 150.0, 5.0, 25.0, 74.0, greenFrom = 60.0, greenTo = 95.0,
                redFrom = 120.0)
            Kind.FILLER_VOLTS -> dial(g, "VOLTS", "DC", 0.0, 30.0, 1.0, 5.0, 27.5, greenFrom = 24.0, greenTo = 29.0)
            Kind.FILLER_HYDRAULIC -> dial(g, "HYD PRESS", "x100", 0.0, 40.0, 1.0, 10.0, 30.0, greenFrom = 25.0,
                greenTo = 34.0)
            Kind.FILLER_OXYGEN -> dial(g, "OXYGEN", "x100", 0.0, 20.0, 1.0, 5.0, 16.0, redFrom = 0.0, redTo = 3.0)
        }
        glass(g)
    }

    /** Clockwise angle from 12 o'clock for a value on a 270 degree dial. */
    private fun sweep(v: Double, lo: Double, hi: Double) = -135.0 + (v.coerceIn(lo, hi) - lo) / (hi - lo) * 270.0

    private fun dial(g: GuiGraphics, title: String, unit: String, lo: Double, hi: Double, minor: Double, major: Double,
                     value: Double, greenFrom: Double? = null, greenTo: Double? = null, redFrom: Double? = null,
                     redTo: Double? = null) {
        DisplayDraw.shapes(g) {
            fun arc(a: Double, b: Double, color: Int) {
                var v = a
                val step = (hi - lo) / 90
                while (v < b) {
                    val n = minOf(v + step, b)
                    val t0 = Math.toRadians(sweep(v, lo, hi)); val t1 = Math.toRadians(sweep(n, lo, hi))
                    val r0 = R - 12; val r1 = R - 2
                    poly(floatArrayOf(C + sin(t0).toFloat() * r0, C - cos(t0).toFloat() * r0,
                        C + sin(t0).toFloat() * r1, C - cos(t0).toFloat() * r1,
                        C + sin(t1).toFloat() * r1, C - cos(t1).toFloat() * r1,
                        C + sin(t1).toFloat() * r0, C - cos(t1).toFloat() * r0), color)
                    v = n
                }
            }
            if (greenFrom != null) arc(greenFrom, greenTo ?: hi, GREEN)
            if (redFrom != null) arc(redFrom, redTo ?: hi, RED)
            var v = lo
            while (v <= hi + 1e-6) {
                val isMajor = abs(v / major - (v / major).roundToInt()) < 1e-6
                tick(C, C, sweep(v, lo, hi), R - if (isMajor) 26 else 14, R - 2, if (isMajor) 5f else 2.5f, MARK)
                v += minor
            }
        }
        var v = lo
        while (v <= hi + 1e-6) {
            val a = Math.toRadians(sweep(v, lo, hi))
            val label = if (v == floor(v)) v.toInt().toString() else v.toString()
            DisplayDraw.text(g, label, C + sin(a).toFloat() * (R - 44), C - cos(a).toFloat() * (R - 44), 1.9f, LABEL, Align.CENTER)
            v += major
        }
        DisplayDraw.text(g, title, C, C + 42f, 1.5f, LABEL, Align.CENTER)
        DisplayDraw.text(g, unit, C, C - 38f, 1.4f, LABEL, Align.CENTER)
        needle(g, sweep(value, lo, hi), R - 18, 7f)
    }

    private fun needle(g: GuiGraphics, deg: Double, length: Float, width: Float, color: Int = NEEDLE, tail: Float = 22f) {
        val a = Math.toRadians(deg)
        val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
        val px = -sy; val py = sx
        DisplayDraw.shapes(g) {
            val w = width / 2
            val tipX = C + sx * length; val tipY = C + sy * length
            val bx = C - sx * tail; val by = C - sy * tail
            poly(floatArrayOf(bx + px * (w + 2), by + py * (w + 2), tipX + sx * 2, tipY + sy * 2,
                bx - px * (w + 2), by - py * (w + 2)), OUTLINE)
            poly(floatArrayOf(bx + px * w, by + py * w, tipX, tipY, bx - px * w, by - py * w), color)
            disc(C, C, 11f, 0f, 0xFF2A2A2A.toInt(), 24)
            disc(C, C, 6f, 0f, 0xFF8A8A8A.toInt(), 24)
        }
    }

    private fun altimeter(g: GuiGraphics, s: FlightDisplayState) {
        DisplayDraw.shapes(g) {
            for (k in 0 until 50) tick(C, C, k * 7.2, if (k % 5 == 0) R - 24 else R - 12, R - 2, if (k % 5 == 0) 5f else 2.5f, MARK)
        }
        for (k in 0 until 10) {
            val a = Math.toRadians(k * 36.0)
            DisplayDraw.text(g, k.toString(), C + sin(a).toFloat() * (R - 44), C - cos(a).toFloat() * (R - 44), 2.4f, LABEL, Align.CENTER)
        }
        DisplayDraw.text(g, "ALT", C, C - 36f, 1.5f, LABEL, Align.CENTER)
        DisplayDraw.text(g, "x100 M", C, C + 38f, 1.3f, LABEL, Align.CENTER)
        val alt = s.altitude
        fun frac(period: Double) = ((alt % period) + period) % period / period * 360.0
        // ten thousands: thin pointer with a triangle at the rim
        val tenK = Math.toRadians(frac(100000.0))
        DisplayDraw.shapes(g) {
            line(C, C, C + sin(tenK).toFloat() * (R - 6), C - cos(tenK).toFloat() * (R - 6), 2.5f, NEEDLE)
            val tx = C + sin(tenK).toFloat() * (R - 6); val ty = C - cos(tenK).toFloat() * (R - 6)
            val px = cos(tenK).toFloat(); val py = sin(tenK).toFloat()
            poly(floatArrayOf(tx + px * 9, ty + py * 9, tx - px * 9, ty - py * 9,
                tx - sin(tenK).toFloat() * 16, ty + cos(tenK).toFloat() * 16), NEEDLE)
        }
        needle(g, frac(10000.0), R * 0.55f, 13f, tail = 14f)
        needle(g, frac(1000.0), R - 14, 7f)
    }

    private fun attitude(g: GuiGraphics, s: FlightDisplayState) {
        val inner = R - 16
        val roll = Math.toRadians(s.rollRight)
        val pitchPx = (s.pitchUp.coerceIn(-40.0, 40.0) * 2.4).toFloat()
        // Horizon: a line through (0, pitch shift) turned by the roll, in dial coordinates.
        val cr = cos(-roll).toFloat(); val sr = sin(-roll).toFloat()
        fun rot(x: Float, y: Float) = floatArrayOf(C + x * cr - y * sr, C + x * sr + y * cr)
        val circle = FloatArray(128) { i ->
            val k = i / 2
            val a = k * 2 * Math.PI / 64
            if (i % 2 == 0) C + cos(a).toFloat() * inner else C + sin(a).toFloat() * inner
        }
        // Signed distance from the horizon (positive = sky side) of a dial point.
        val nx = -sr; val ny = cr            // screen-down direction of the rolled frame
        fun side(x: Float, y: Float) = -((x - C) * nx + (y - C) * ny - pitchPx)
        fun clip(keepSky: Boolean): FloatArray {
            val out = ArrayList<Float>()
            val n = circle.size / 2
            for (k in 0 until n) {
                val ax = circle[2 * k]; val ay = circle[2 * k + 1]
                val bx = circle[(2 * k + 2) % circle.size]; val by = circle[(2 * k + 3) % circle.size]
                val da = side(ax, ay) * (if (keepSky) 1 else -1); val db = side(bx, by) * (if (keepSky) 1 else -1)
                if (da >= 0) { out.add(ax); out.add(ay) }
                if ((da >= 0) != (db >= 0)) {
                    val t = da / (da - db)
                    out.add(ax + (bx - ax) * t); out.add(ay + (by - ay) * t)
                }
            }
            return out.toFloatArray()
        }
        DisplayDraw.shapes(g) {
            disc(C, C, R, inner, 0xFF1E1E1E.toInt())
            val sky = clip(true); if (sky.size >= 6) poly(sky, SKY)
            val ground = clip(false); if (ground.size >= 6) poly(ground, GROUND)
            // horizon line and pitch marks, within the dial
            val hl = rot(-inner, pitchPx); val hr = rot(inner, pitchPx)
            line(hl[0], hl[1], hr[0], hr[1], 3f, MARK)
            for (deg in intArrayOf(-20, -10, 10, 20)) {
                val y = pitchPx - deg * 2.4f
                if (abs(y) > inner - 20) continue
                val half = if (abs(deg) == 20) 30f else 20f
                val a = rot(-half, y); val b = rot(half, y)
                line(a[0], a[1], b[0], b[1], 2.5f, MARK)
            }
            // bank scale on the rim (fixed) and the sky pointer turning with the horizon
            for (a in intArrayOf(-60, -30, -20, -10, 0, 10, 20, 30, 60))
                tick(C, C, a.toDouble(), R - if (a % 30 == 0) 16 else 9, R - 1, if (a % 30 == 0) 4f else 2.5f, MARK)
            val p0 = rot(0f, -(inner - 2)); val p1 = rot(-8f, -(inner - 16)); val p2 = rot(8f, -(inner - 16))
            poly(floatArrayOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1]), ORANGE)
            // fixed miniature aircraft
            line(C - 62, C, C - 20, C, 6f, ORANGE)
            line(C + 20, C, C + 62, C, 6f, ORANGE)
            line(C - 20, C, C, C + 14, 6f, ORANGE)
            line(C, C + 14, C + 20, C, 6f, ORANGE)
            disc(C, C, 4f, 0f, ORANGE, 16)
        }
    }

    private fun heading(g: GuiGraphics, s: FlightDisplayState) {
        val h = s.heading
        DisplayDraw.shapes(g) {
            var d = 0
            while (d < 360) {
                val major = d % 10 == 0
                tick(C, C, d - h, if (major) R - 22 else R - 12, R - 2, if (major) 4.5f else 2.5f, MARK)
                d += 5
            }
            // lubber line and index marks on the case
            poly(floatArrayOf(C - 8, 4f, C + 8, 4f, C, 20f), ORANGE)
            for (a in intArrayOf(45, 90, 135, 180, 225, 270, 315)) tick(C, C, a.toDouble(), R + 1, R + 7, 3f, ORANGE)
            // aircraft silhouette, nose up
            line(C, C - 34, C, C + 30, 6f, ORANGE)
            line(C - 30, C - 4, C + 30, C - 4, 6f, ORANGE)
            line(C - 13, C + 26, C + 13, C + 26, 5f, ORANGE)
        }
        for (d in 0 until 360 step 30) {
            val a = Math.toRadians(d - h)
            val label = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> (d / 10).toString() }
            DisplayDraw.text(g, label, C + sin(a).toFloat() * (R - 40), C - cos(a).toFloat() * (R - 40),
                if (d % 90 == 0) 2.4f else 1.9f, if (d % 90 == 0) YELLOW else LABEL, Align.CENTER)
        }
    }

    private fun fuel(g: GuiGraphics) {
        DisplayDraw.shapes(g) {
            for (k in 0..16) tick(C, C, -90.0 + k * 11.25, if (k % 4 == 0) R - 26 else R - 13, R - 2,
                if (k % 4 == 0) 5f else 2.5f, if (k < 2) RED else MARK)
        }
        for ((k, label) in arrayOf("E", "1/4", "1/2", "3/4", "F").withIndex()) {
            val a = Math.toRadians(-90.0 + k * 45.0)
            DisplayDraw.text(g, label, C + sin(a).toFloat() * (R - 46), C - cos(a).toFloat() * (R - 46), 1.9f, LABEL, Align.CENTER)
        }
        DisplayDraw.text(g, "FUEL", C, C + 40f, 1.7f, LABEL, Align.CENTER)
        needle(g, -90.0 + 0.72 * 180.0, R - 18, 7f)
    }

    private fun clock(g: GuiGraphics) {
        DisplayDraw.shapes(g) {
            for (k in 0 until 60) tick(C, C, k * 6.0, if (k % 5 == 0) R - 20 else R - 10, R - 2, if (k % 5 == 0) 5f else 2f, MARK)
        }
        for (k in 1..12) {
            val a = Math.toRadians(k * 30.0)
            DisplayDraw.text(g, k.toString(), C + sin(a).toFloat() * (R - 40), C - cos(a).toFloat() * (R - 40), 2.0f, LABEL, Align.CENTER)
        }
        needle(g, 10 * 30.0 + 5.0, R * 0.55f, 10f, tail = 12f)
        needle(g, 2 * 30.0, R - 16, 6f)
    }

    /** A faint reflection across the glass. */
    private fun glass(g: GuiGraphics) {
        DisplayDraw.shapes(g) {
            val steps = 24
            for (k in 0 until steps) {
                val a0 = Math.toRadians(-70.0 + k * 50.0 / steps); val a1 = Math.toRadians(-70.0 + (k + 1) * 50.0 / steps)
                val r0 = R - 10; val r1 = R - 26
                poly(floatArrayOf(C + sin(a0).toFloat() * r0, C - cos(a0).toFloat() * r0,
                    C + sin(a1).toFloat() * r0, C - cos(a1).toFloat() * r0,
                    C + sin(a1).toFloat() * r1, C - cos(a1).toFloat() * r1,
                    C + sin(a0).toFloat() * r1, C - cos(a0).toFloat() * r1), 0x18FFFFFF)
            }
        }
    }
}
