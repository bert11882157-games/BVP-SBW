package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.client.flightdisplay.DisplayDraw.Align
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the radar page (a B-scope: bearing across, range up) into the currently bound [SIZE] x [SIZE] target:
 * soft-key legends along the edges next to the bezel buttons, range and azimuth grid, the scan bar, air contacts
 * (filled, with altitude difference), ground contacts (hollow) and the locked target with its data block.
 */
internal object RadarDisplayPainter {
    const val SIZE = PrimaryFlightDisplayPainter.SIZE

    private const val L = 70; private const val R = 442
    private const val T = 70; private const val B = 432
    private const val CX = (L + R) / 2

    /** Legends line up with the bezel buttons: 5 along the top and bottom, 6 down each side. */
    fun buttonX(k: Int) = SIZE * (k + 1) / 6f
    fun buttonY(k: Int) = SIZE * (k + 1) / 7f

    fun paint(g: GuiGraphics, s: RadarDisplayState, scale: Int, p: DisplayPalette) {
        g.fill(0, 0, SIZE, SIZE, p.background)
        legends(g, s, p)
        grid(g, s, p)
        DisplayDraw.scissor(SIZE, L, T, R, B, scale)
        if (s.live) {
            val x = xOf(s.sweep)
            g.fill(x.roundToInt() - 1, T, x.roundToInt() + 2, B, (p.green and 0x00FFFFFF) or 0x90000000.toInt())
            for (c in s.contacts.sortedBy { it.locked }) contact(g, c, s, p)
        }
        com.mojang.blaze3d.systems.RenderSystem.disableScissor()
        DisplayDraw.outline(g, L, T, R, B, 2, p.frame)
        if (!s.live) {
            g.fill(CX - 70, (T + B) / 2 - 18, CX + 70, (T + B) / 2 + 18, p.black)
            DisplayDraw.outline(g, CX - 70, (T + B) / 2 - 18, CX + 70, (T + B) / 2 + 18, 2, p.dim)
            DisplayDraw.text(g, "STBY", CX.toFloat(), (T + B) / 2f, 2.2f, p.dim, Align.CENTER)
        }
        lockedData(g, s, p)
        DisplayDraw.outline(g, 2, 2, SIZE - 2, SIZE - 2, 2, p.bezelLine)
        p.finish(g, SIZE)
    }

    private fun xOf(bearing: Double) = (CX + bearing / RadarDisplayState.AZIMUTH_LIMIT * (R - CX)).toFloat()
    private fun yOf(range: Double, scale: Double) = (B - range / scale * (B - T)).toFloat()

    private fun legends(g: GuiGraphics, s: RadarDisplayState, p: DisplayPalette) {
        val top = arrayOf("CRM", "RWS", "TWS", "SCAN", "MENU")
        for ((k, label) in top.withIndex()) {
            val color = if (k == 1 && s.live) p.white else p.cyan
            DisplayDraw.text(g, label, buttonX(k), 18f, 1.6f, color, Align.CENTER)
            if (k == 1 && s.live) DisplayDraw.outline(g, (buttonX(k) - 24).roundToInt(), 8, (buttonX(k) + 24).roundToInt(), 29, 2, p.white)
        }
        val bottom = arrayOf("SWAP", "HSD", "FCR", "SMS", "DCLT")
        for ((k, label) in bottom.withIndex()) {
            DisplayDraw.text(g, label, buttonX(k), SIZE - 17f, 1.6f, if (k == 2) p.white else p.cyan, Align.CENTER)
        }
        // Range up / down beside the top-left buttons, azimuth and bars below them.
        DisplayDraw.text(g, "▲", 14f, buttonY(0), 1.6f, p.cyan, Align.LEFT)
        DisplayDraw.text(g, (s.rangeScale.roundToInt()).toString(), 14f, buttonY(1), 1.6f, p.white, Align.LEFT)
        DisplayDraw.text(g, "▼", 14f, buttonY(2), 1.6f, p.cyan, Align.LEFT)
        DisplayDraw.text(g, "A6", 14f, buttonY(4), 1.6f, p.cyan, Align.LEFT)
        DisplayDraw.text(g, "4B", 14f, buttonY(5), 1.6f, p.cyan, Align.LEFT)
        DisplayDraw.text(g, "HDG", SIZE - 14f, buttonY(0), 1.4f, p.cyan, Align.RIGHT)
        val hdg = (s.heading.roundToInt() % 360).let { if (it == 0) 360 else it }
        DisplayDraw.text(g, hdg.toString().padStart(3, '0'), SIZE - 14f, buttonY(1), 1.6f, p.white, Align.RIGHT)
        DisplayDraw.text(g, "TGT", SIZE - 14f, buttonY(3), 1.4f, p.cyan, Align.RIGHT)
        DisplayDraw.text(g, s.contacts.size.toString(), SIZE - 14f, buttonY(4), 1.6f, p.white, Align.RIGHT)
    }

    private fun grid(g: GuiGraphics, s: RadarDisplayState, p: DisplayPalette) {
        g.fill(L, T, R, B, p.black)
        // Range lines at quarters, dashed.
        for (q in 1..3) {
            val y = (T + (B - T) * q / 4f).roundToInt()
            var x = L + 4
            while (x < R) { g.fill(x, y, minOf(x + 10, R), y + 2, p.dim); x += 22 }
            val label = (s.rangeScale * (4 - q) / 4).roundToInt().toString()
            DisplayDraw.text(g, label, L + 6f, y - 9f, 1.2f, p.dim, Align.LEFT)
        }
        // Azimuth ticks every 10 degrees along the bottom, lines at 30 degrees.
        var a = -60
        while (a <= 60) {
            val x = xOf(a.toDouble()).roundToInt()
            val major = a % 30 == 0
            g.fill(x - 1, B - (if (major) 14 else 7), x + 1, B, p.frame)
            if (major && a != -60 && a != 60) {
                var y = T + 4
                while (y < B - 16) { g.fill(x, y, x + 2, minOf(y + 6, B - 16), p.dim); y += 18 }
            }
            a += 10
        }
        // Own-ship caret below the scope.
        DisplayDraw.triangle(g, CX - 9f, B + 16f, CX + 9f, B + 16f, CX.toFloat(), B + 4f, p.white)
    }

    private fun contact(g: GuiGraphics, c: RadarDisplayState.Contact, s: RadarDisplayState, p: DisplayPalette) {
        val x = xOf(c.bearing)
        val y = yOf(c.range, s.rangeScale)
        val half = 9
        val color = if (c.locked) p.amber else if (c.air) p.white else p.green
        if (c.air) {
            g.fill((x - half).roundToInt(), (y - half).roundToInt(), (x + half).roundToInt(), (y + half).roundToInt(), color)
            // Altitude difference in tens of blocks.
            val alt = (c.relativeAltitude / 10).roundToInt()
            DisplayDraw.text(g, (if (alt > 0) "+" else "") + alt, x, y + 16f, 1.3f, color, Align.CENTER)
        } else {
            DisplayDraw.outline(g, (x - half + 1).roundToInt(), (y - half + 1).roundToInt(),
                (x + half - 1).roundToInt(), (y + half - 1).roundToInt(), 2, color)
        }
        if (c.locked) {
            DisplayDraw.disc(g, x, y, 17f, 14f, p.amber, 40)
            DisplayDraw.line(g, x, y - 17f, x, y - 30f, 3f, p.amber)
        }
    }

    private fun lockedData(g: GuiGraphics, s: RadarDisplayState, p: DisplayPalette) {
        val c = s.contacts.firstOrNull { it.locked } ?: return
        val text = "B" + c.bearing.roundToInt().let { if (it >= 0) "R$it" else "L${abs(it)}" } +
            "  R" + c.range.roundToInt() + "  Vc" + c.closing.roundToInt()
        DisplayDraw.text(g, text, CX.toFloat(), T - 16f, 1.5f, p.amber, Align.CENTER)
    }
}
