package com.atsuishio.superbwarfare.client.flightdisplay

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.gui.GuiGraphics

/**
 * A gunsight's reticle, painted into the head-up display's target in place of [HudPainter]'s symbology (HUD Style
 * "GYRO" or "REFLECTOR"). Like the HUD picture it is collimated: [HudPainter.Frame] maps degrees off the nose to
 * texels as seen from the design eye, so the reticle sits over the world.
 *
 *  * REFLECTOR (PBP-1, Revi and the like): a fixed 100-mil ring with a centre dot and a broken cross, on the
 *    boresight.
 *  * GYRO (ASP-3N to ASP-17, K-14): the fixed cross on the boresight, and the pipper with its ring of diamonds,
 *    displaced from it by the lead the gyro computes: against the aircraft's rotation (pull up and the pipper drops,
 *    yaw right and it moves left) times the projectile's time of flight, plus a little gravity drop. The lead is
 *    smoothed as the gyro's own lag would.
 */
internal object GunsightPainter {
    private const val TIME_OF_FLIGHT = 0.7       // s, the fixed range the ring is set for (about 600 m)
    private const val GRAVITY_DROP = 0.6         // degrees
    private const val MAX_LEAD = 9.0             // degrees, the gyro's stop
    private const val LAG = 0.25                 // s, the gyro's time constant
    private const val MIL = 0.0573               // degrees per mil

    private var lastNanos = 0L
    private var lastPitch = 0.0
    private var leadRight = 0.0
    private var leadUp = -GRAVITY_DROP

    fun paint(g: GuiGraphics, s: FlightDisplayState, f: HudPainter.Frame, rgb: Int, gyro: Boolean, live: Boolean) {
        val color = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
        g.fill(0, 0, HudPainter.SIZE, HudPainter.SIZE, 0xFF000000.toInt())
        val k = f.pxPerDeg
        val bx = f.boreX
        val by = f.boreY
        if (!gyro) {
            DisplayDraw.shapes(g) {
                disc(bx, by, (50 * MIL * k).toFloat() + 2.5f, (50 * MIL * k).toFloat() - 2.5f, color, 64)
                disc(bx, by, 3.5f, 0f, color, 16)
                val r0 = (8 * MIL * k).toFloat(); val r1 = (40 * MIL * k).toFloat()
                line(bx - r1, by, bx - r0, by, 3.5f, color); line(bx + r0, by, bx + r1, by, 3.5f, color)
                line(bx, by - r1, bx, by - r0, 3.5f, color); line(bx, by + r0, bx, by + r1, 3.5f, color)
            }
            return
        }
        // the lead: rates from the change in attitude, smoothed with the gyro's lag
        val now = System.nanoTime()
        if (live) {
            val dt = if (lastNanos == 0L) 0.0 else ((now - lastNanos) / 1e9).coerceIn(0.0, 0.2)
            if (dt > 1e-4) {
                val pitchRate = (s.pitchUp - lastPitch) / dt
                val wantRight = (-s.yawRate * TIME_OF_FLIGHT).coerceIn(-MAX_LEAD, MAX_LEAD)
                val wantUp = (-pitchRate * TIME_OF_FLIGHT - GRAVITY_DROP).coerceIn(-MAX_LEAD, MAX_LEAD)
                val a = (dt / (LAG + dt))
                leadRight += (wantRight - leadRight) * a
                leadUp += (wantUp - leadUp) * a
                val l = sqrt(leadRight * leadRight + leadUp * leadUp)
                if (l > MAX_LEAD) { leadRight *= MAX_LEAD / l; leadUp *= MAX_LEAD / l }
            }
            lastNanos = now
            lastPitch = s.pitchUp
        }
        val ringR = (50 * MIL * k).toFloat()
        // the pipper stays on the glass with its ring (on a glass too small for the ring, at the glass's middle)
        val px = keepOn(if (live) (bx + leadRight * k).toFloat() else bx, f.left + ringR + 6, f.right - ringR - 6)
        val py = keepOn(if (live) (by - leadUp * k).toFloat() else (by + GRAVITY_DROP * k).toFloat(),
            f.top + ringR + 6, f.bottom - ringR - 6)
        DisplayDraw.shapes(g) {
            // the fixed cross on the boresight
            val c0 = (6 * MIL * k).toFloat(); val c1 = (18 * MIL * k).toFloat()
            line(bx - c1, by, bx - c0, by, 3.5f, color); line(bx + c0, by, bx + c1, by, 3.5f, color)
            line(bx, by - c1, bx, by - c0, 3.5f, color); line(bx, by + c0, bx, by + c1, 3.5f, color)
            // the pipper and its ring of eight diamonds
            disc(px, py, 5f, 0f, color, 16)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val x = px + (ringR * cos(a)).toFloat(); val y = py + (ringR * sin(a)).toFloat()
                val d = 9f
                poly(floatArrayOf(x, y - d, x + d, y, x, y + d, x - d, y), color)
            }
        }
    }

    /** v kept within [lo, hi]; the middle of the two when the range is empty (coerceIn would throw) */
    private fun keepOn(v: Float, lo: Float, hi: Float): Float = if (lo <= hi) v.coerceIn(lo, hi) else (lo + hi) / 2
}
