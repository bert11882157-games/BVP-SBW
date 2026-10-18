package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.client.flightdisplay.DisplayDraw.Align
import net.minecraft.client.gui.GuiGraphics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Head-up display symbology, painted into the HUD's [SIZE] x [SIZE] target (GUI projection, y down, black = no
 * light: the image is added onto the combiner glass).
 *
 * The picture is conformal: [Frame] maps degrees off the aircraft's nose to texels as seen from the design eye, so
 * the horizon line, pitch ladder and flight path marker lie over the world they describe. Around them: heading tape
 * on top, airspeed (km/h) on the left with Mach, G and angle of attack under it, altitude on the right with
 * vertical speed, a bank scale below, and the missile and lock warnings.
 */
internal object HudPainter {
    const val SIZE = 512

    /**
     * Where things go in the target: [pxPerDeg] texels per degree, the boresight (the aircraft's nose axis) at
     * [boreX], [boreY], and the part of the target the combiner glass shows, [left]..[right] x [top]..[bottom].
     */
    class Frame(val pxPerDeg: Float, val boreX: Float, val boreY: Float,
                val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Warnings shown on the HUD. */
    class Alerts(val locked: Boolean, val launch: Boolean)

    private var color = 0xFF4CFF6A.toInt()

    fun paint(g: GuiGraphics, s: FlightDisplayState, f: Frame, alerts: Alerts, rgb: Int) {
        color = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
        g.fill(0, 0, SIZE, SIZE, 0xFF000000.toInt())
        val k = f.pxPerDeg
        val cx = (f.left + f.right) / 2
        val w = f.right - f.left
        val h = f.bottom - f.top
        // Flight path marker: where the aircraft is going. Kept inside the glass; caged at the boresight while
        // there is no flight path (parked, hovering).
        val pathRight = s.pathRight
        val pathUp = s.pathUp
        var fpmX = f.boreX + ((pathRight ?: 0.0) * k).toFloat()
        var fpmY = f.boreY - ((pathUp ?: 0.0) * k).toFloat()
        val margin = 26f
        val clamped = fpmX < f.left + margin || fpmX > f.right - margin || fpmY < f.top + margin || fpmY > f.bottom - margin
        fpmX = fpmX.coerceIn(f.left + margin, f.right - margin)
        fpmY = fpmY.coerceIn(f.top + margin, f.bottom - margin)

        ladder(g, s, f, fpmX, fpmY)
        waterline(g, f.boreX, f.boreY)
        if (pathRight != null && pathUp != null) fpm(g, fpmX, fpmY, clamped)
        headingTape(g, s.heading, cx, f.top + h * 0.09f, w)
        val tapeY = (f.boreY).coerceIn(f.top + h * 0.3f, f.bottom - h * 0.3f)
        speedTape(g, s.speedKmh, f.left + w * 0.13f, tapeY, h)
        altitudeTape(g, s.altitude, f.right - w * 0.13f, tapeY, h)
        bankScale(g, s.rollRight, cx, f.bottom - h * 0.13f, w)

        // readouts under the speed tape and the altitude tape
        val lx = f.left + w * 0.05f
        var ly = tapeY + h * 0.28f
        val line = h * 0.055f
        s.mach?.let { text(g, String.format(Locale.ROOT, "M %.2f", it), lx, ly, 1.5f, Align.LEFT); ly += line }
        s.liftG?.let { text(g, String.format(Locale.ROOT, "G %.1f", it), lx, ly, 1.5f, Align.LEFT); ly += line }
        s.angleOfAttack?.let { text(g, String.format(Locale.ROOT, "α %.1f", it), lx, ly, 1.5f, Align.LEFT) }
        val rx = f.right - w * 0.05f
        text(g, String.format(Locale.ROOT, "%+d VS", s.verticalSpeed.roundToInt()), rx, tapeY + h * 0.28f, 1.5f, Align.RIGHT)
        s.throttle?.let { text(g, String.format(Locale.ROOT, "THR %d", (it * 100).roundToInt()), rx, tapeY + h * 0.28f + line,
            1.5f, Align.RIGHT) }

        val blink = (System.currentTimeMillis() / 300L) % 2L == 0L
        if (alerts.launch && blink) text(g, "BREAK", cx, f.boreY + h * 0.18f, 3.0f, Align.CENTER)
        else if (alerts.locked) text(g, "LOCK", cx, f.boreY + h * 0.18f, 2.0f, Align.CENTER)
    }

    // ------------------------------------------------------------------ symbols

    private fun text(g: GuiGraphics, s: String, x: Float, y: Float, size: Float, align: Align) =
        DisplayDraw.text(g, s, x, y, size, color, align)

    /** The aircraft's nose (gun cross / waterline): a flat W. */
    private fun waterline(g: GuiGraphics, x: Float, y: Float) {
        DisplayDraw.shapes(g) {
            line(x - 26, y, x - 13, y, 3f, color)
            line(x - 13, y, x - 6, y + 9, 3f, color)
            line(x - 6, y + 9, x, y, 3f, color)
            line(x, y, x + 6, y + 9, 3f, color)
            line(x + 6, y + 9, x + 13, y, 3f, color)
            line(x + 13, y, x + 26, y, 3f, color)
        }
    }

    /** Flight path marker: a circle with wings and a fin; dashed wings while held at the edge of the glass. */
    private fun fpm(g: GuiGraphics, x: Float, y: Float, clamped: Boolean) {
        DisplayDraw.shapes(g) {
            disc(x, y, 10f, 7f, color, 28)
            if (clamped) {
                line(x - 28, y, x - 22, y, 3f, color); line(x - 17, y, x - 11, y, 3f, color)
                line(x + 11, y, x + 17, y, 3f, color); line(x + 22, y, x + 28, y, 3f, color)
            } else {
                line(x - 28, y, x - 10, y, 3f, color)
                line(x + 10, y, x + 28, y, 3f, color)
            }
            line(x, y - 10, x, y - 20, 3f, color)
        }
    }

    /**
     * Pitch ladder: a rung every 5 degrees of pitch, solid above the horizon with tips toward it, dashed below;
     * turned with the bank and centred on the flight path marker across the rungs.
     */
    private fun ladder(g: GuiGraphics, s: FlightDisplayState, f: Frame, fpmX: Float, fpmY: Float) {
        val k = f.pxPerDeg
        val roll = Math.toRadians(-s.rollRight)
        val cr = cos(roll).toFloat(); val sr = sin(roll).toFloat()
        // ladder-frame x (along the rungs) of the marker, so the ladder follows it sideways
        val shift = (fpmX - f.boreX) * cr + (fpmY - f.boreY) * sr
        fun at(x: Float, y: Float) = floatArrayOf(f.boreX + x * cr - y * sr, f.boreY + x * sr + y * cr)
        val spanDeg = (f.bottom - f.top) / k * 0.75
        val lo = floor((s.pitchUp - spanDeg) / 5.0).toInt() * 5
        val hi = floor((s.pitchUp + spanDeg) / 5.0).toInt() * 5 + 5
        // rung sizes in degrees, kept legible on very small or very large combiners
        val gap = (0.8f * k).coerceIn(18f, 40f)
        val half = (2.5f * k).coerceIn(48f, 120f)
        val horizon = (6f * k).coerceIn(120f, 220f)
        DisplayDraw.shapes(g) {
            var p = maxOf(lo, -90)
            while (p <= minOf(hi, 90)) {
                val y = ((s.pitchUp - p) * k).toFloat()
                if (p == 0) {
                    val a = at(shift - horizon, y); val b = at(shift - gap * 1.6f, y)
                    val c = at(shift + gap * 1.6f, y); val d = at(shift + horizon, y)
                    line(a[0], a[1], b[0], b[1], 3f, color)
                    line(c[0], c[1], d[0], d[1], 3f, color)
                } else {
                    val tip = if (p > 0) 10f else -10f            // tips point toward the horizon
                    for (side in floatArrayOf(-1f, 1f)) {
                        val x0 = shift + side * gap; val x1 = shift + side * half
                        if (p > 0) {
                            val a = at(x0, y); val b = at(x1, y)
                            line(a[0], a[1], b[0], b[1], 3f, color)
                        } else {
                            // dashed, each half bent down toward the horizon by half the dive angle
                            val bend = Math.toRadians(-p / 2.0).toFloat()
                            val n = 3
                            for (j in 0 until n) {
                                val t0 = j.toFloat() / n; val t1 = (j + 0.6f) / n
                                val ax = x0 + (x1 - x0) * t0; val bx = x0 + (x1 - x0) * t1
                                val ay = y - abs(ax - x0) * kotlin.math.tan(bend); val by = y - abs(bx - x0) * kotlin.math.tan(bend)
                                val a = at(ax, ay); val b = at(bx, by)
                                line(a[0], a[1], b[0], b[1], 3f, color)
                            }
                        }
                        val endY = if (p > 0) y else y - abs(x1 - x0) * kotlin.math.tan(Math.toRadians(-p / 2.0).toFloat())
                        val e0 = at(x1, endY); val e1 = at(x1, endY + tip)
                        line(e0[0], e0[1], e1[0], e1[1], 3f, color)
                    }
                }
                p += 5
            }
        }
        // rung numbers, beside the outer ends
        var p = maxOf(lo, -90)
        while (p <= minOf(hi, 90)) {
            if (p != 0) {
                val y = ((s.pitchUp - p) * k).toFloat()
                val bend = if (p < 0) (half - gap) * kotlin.math.tan(Math.toRadians(-p / 2.0).toFloat()) else 0f
                for (side in floatArrayOf(-1f, 1f)) {
                    val q = at(shift + side * (half + 22f), y - bend)
                    if (q[0] in f.left..f.right && q[1] in f.top..f.bottom)
                        text(g, abs(p).toString(), q[0], q[1], 1.5f, Align.CENTER)
                }
            }
            p += 5
        }
    }

    private fun headingTape(g: GuiGraphics, heading: Double, cx: Float, y: Float, width: Float) {
        val pxPerDeg = width * 0.6f / 40f
        DisplayDraw.shapes(g) {
            var d = floor((heading - 22) / 5.0).toInt() * 5
            while (d <= heading + 22) {
                val x = cx + ((d - heading) * pxPerDeg).toFloat()
                val major = Math.floorMod(d, 10) == 0
                line(x, y, x, y + if (major) 14f else 8f, 3f, color)
                d += 5
            }
            // index below the tape
            poly(floatArrayOf(cx, y + 18f, cx - 8f, y + 30f, cx + 8f, y + 30f), color)
        }
        var d = floor((heading - 22) / 10.0).toInt() * 10
        while (d <= heading + 22) {
            val x = cx + ((d - heading) * pxPerDeg).toFloat()
            if (abs(x - cx) > 30f) text(g, String.format(Locale.ROOT, "%02d", Math.floorMod(d, 360) / 10), x, y - 14f, 1.6f, Align.CENTER)
            d += 10
        }
        DisplayDraw.shapes(g) {
            val bw = 28f; val by = y - 14f
            line(cx - bw, by - 12f, cx + bw, by - 12f, 2f, color); line(cx - bw, by + 12f, cx + bw, by + 12f, 2f, color)
            line(cx - bw, by - 12f, cx - bw, by + 12f, 2f, color); line(cx + bw, by - 12f, cx + bw, by + 12f, 2f, color)
        }
        text(g, String.format(Locale.ROOT, "%03d", Math.floorMod(heading.roundToInt(), 360)), cx, y - 14f, 1.8f, Align.CENTER)
    }

    /** A vertical tape: the value in a box at [y], scale marks moving past it. */
    private fun tape(g: GuiGraphics, value: Double, x: Float, y: Float, height: Float, step: Double, labelEvery: Double,
                     pxPerUnit: Float, rightSide: Boolean, label: (Double) -> String, box: String) {
        val span = height * 0.22f
        val dir = if (rightSide) 1f else -1f
        DisplayDraw.shapes(g) {
            var v = floor((value - span / pxPerUnit) / step) * step
            while (v <= value + span / pxPerUnit) {
                val ty = y - ((v - value) * pxPerUnit).toFloat()
                if (abs(ty - y) > 16f && abs(ty - y) <= span) {
                    val major = abs(v / labelEvery - (v / labelEvery).roundToInt()) < 1e-6
                    line(x, ty, x + dir * (if (major) 14f else 8f), ty, 3f, color)
                }
                v += step
            }
            line(x, y - span, x, y + span, 2f, color)
        }
        var v = floor((value - span / pxPerUnit) / labelEvery) * labelEvery
        while (v <= value + span / pxPerUnit) {
            val ty = y - ((v - value) * pxPerUnit).toFloat()
            if (abs(ty - y) > 22f && abs(ty - y) <= span - 6f && v >= 0) text(g, label(v), x + dir * 20f, ty, 1.4f, if (rightSide) Align.LEFT else Align.RIGHT)
            v += labelEvery
        }
        val bw = 46f
        val bx = x - dir * 6f
        DisplayDraw.shapes(g) {
            val x0 = if (rightSide) bx else bx - bw
            val x1 = if (rightSide) bx + bw else bx
            line(x0, y - 13f, x1, y - 13f, 2f, color); line(x0, y + 13f, x1, y + 13f, 2f, color)
            line(x0, y - 13f, x0, y + 13f, 2f, color); line(x1, y - 13f, x1, y + 13f, 2f, color)
        }
        text(g, box, if (rightSide) bx + bw / 2 else bx - bw / 2, y, 1.8f, Align.CENTER)
    }

    private fun speedTape(g: GuiGraphics, kmh: Double, x: Float, y: Float, height: Float) =
        tape(g, kmh, x, y, height, 10.0, 50.0, 1.6f, rightSide = false, label = { it.roundToInt().toString() },
            box = kmh.coerceAtLeast(0.0).roundToInt().toString())

    private fun altitudeTape(g: GuiGraphics, alt: Double, x: Float, y: Float, height: Float) =
        tape(g, alt, x, y, height, 10.0, 50.0, 1.6f, rightSide = true, label = { it.roundToInt().toString() },
            box = alt.roundToInt().toString())

    /** Bank scale below the ladder: fixed marks at 0, 10, 20, 30 and 45 degrees each way, a pointer that turns. */
    private fun bankScale(g: GuiGraphics, roll: Double, cx: Float, cy: Float, width: Float) {
        val r = width * 0.16f
        val centreY = cy - r
        DisplayDraw.shapes(g) {
            for (a in intArrayOf(-45, -30, -20, -10, 0, 10, 20, 30, 45)) {
                tick(cx, centreY, 180.0 + a, r, r + if (a % 30 == 0 || a == 0) 12f else 7f, 3f, color)
            }
            // pointer: turns with the bank, pointing at the scale (a level wing points at 0)
            val a = Math.toRadians(180.0 - roll.coerceIn(-60.0, 60.0))
            val sx = sin(a).toFloat(); val sy = -cos(a).toFloat()
            val px = cx + sx * (r - 3); val py = centreY + sy * (r - 3)
            val nx = -sy; val ny = sx
            poly(floatArrayOf(px, py, px - sx * 12 + nx * 6, py - sy * 12 + ny * 6, px - sx * 12 - nx * 6,
                py - sy * 12 - ny * 6), color)
        }
    }
}
