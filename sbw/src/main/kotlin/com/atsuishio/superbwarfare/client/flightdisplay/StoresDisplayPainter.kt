package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.client.flightdisplay.DisplayDraw.Align
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the stores management page into the currently bound [SIZE] x [SIZE] target: a top-down wireframe of the
 * aircraft (nose up) with every pylon marked - loaded stations with a symbol for the kind of store and its name and
 * count, empty pylons as hollow boxes, internal bays dashed - the gun and payload along the bottom and soft-key
 * legends by the bezel buttons.
 */
internal object StoresDisplayPainter {
    const val SIZE = PrimaryFlightDisplayPainter.SIZE

    private const val L = 44f; private const val R = 468f
    private const val T = 58f; private const val B = 404f

    fun paint(g: GuiGraphics, s: StoresDisplayState, scale: Int, p: DisplayPalette) {
        g.fill(0, 0, SIZE, SIZE, p.background)
        legends(g, s, p)
        if (!s.live) {
            DisplayDraw.text(g, "SMS", SIZE / 2f, SIZE / 2f - 20f, 3.0f, p.dim, Align.CENTER)
            DisplayDraw.text(g, "STBY", SIZE / 2f, SIZE / 2f + 18f, 2.0f, p.dim, Align.CENTER)
        } else {
            diagram(g, s, p)
            footer(g, s, p)
        }
        DisplayDraw.outline(g, 2, 2, SIZE - 2, SIZE - 2, 2, p.bezelLine)
        p.finish(g, SIZE)
    }

    private fun legends(g: GuiGraphics, s: StoresDisplayState, p: DisplayPalette) {
        val top = arrayOf("S-J", "A-G", "A-A", "INV", "MENU")
        for ((k, label) in top.withIndex()) {
            val x = RadarDisplayPainter.buttonX(k)
            DisplayDraw.text(g, label, x, 18f, 1.6f, if (k == 3) p.white else p.cyan, Align.CENTER)
            if (k == 3) DisplayDraw.outline(g, (x - 22).roundToInt(), 8, (x + 22).roundToInt(), 29, 2, p.white)
        }
        val bottom = arrayOf("SWAP", "HSD", "SMS", "FCR", "DCLT")
        for ((k, label) in bottom.withIndex()) {
            DisplayDraw.text(g, label, RadarDisplayPainter.buttonX(k), SIZE - 17f, 1.6f, if (k == 2) p.white else p.cyan, Align.CENTER)
        }
    }

    private fun diagram(g: GuiGraphics, s: StoresDisplayState, p: DisplayPalette) {
        // Fit the planform (and every station) into the diagram box, nose up: screen x = -local x, y = -local z.
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        fun include(x: Double, z: Double) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        for (pl in s.planform) { var i = 0; while (i + 1 < pl.size) { include(pl[i], pl[i + 1]); i += 2 } }
        for (st in s.stations) include(st.x, st.z)
        if (minX > maxX) { minX = -1.0; maxX = 1.0; minZ = -1.0; maxZ = 1.0 }
        val span = maxOf(maxX - minX, 0.5); val length = maxOf(maxZ - minZ, 0.5)
        val k = min((R - L) / span, (B - T) / length).toFloat() * 0.94f
        val cx = (L + R) / 2f; val cy = (T + B) / 2f
        val mx = (minX + maxX) / 2; val mz = (minZ + maxZ) / 2
        fun sx(x: Double) = cx - ((x - mx) * k).toFloat()
        fun sy(z: Double) = cy - ((z - mz) * k).toFloat()

        DisplayDraw.shapes(g) {
            for (pl in s.planform) {
                var i = 0
                while (i + 3 < pl.size) {
                    line(sx(pl[i]), sy(pl[i + 1]), sx(pl[i + 2]), sy(pl[i + 3]), 2.2f, p.green)
                    i += 2
                }
                if (pl.size >= 4) line(sx(pl[pl.size - 2]), sy(pl[pl.size - 1]), sx(pl[0]), sy(pl[1]), 2.2f, p.green)
            }
            // Centre line.
            var y = T
            while (y < B) { line(cx - ((0 - mx) * k).toFloat(), y, cx - ((0 - mx) * k).toFloat(), minOf(y + 8f, B), 1.2f, p.dim); y += 16f }
        }
        // Stations, labels alternating above and below so neighbours do not collide.
        val ordered = s.stations.withIndex().sortedBy { it.value.x }
        for ((n, iv) in ordered.withIndex()) {
            val st = iv.value
            val x = sx(st.x); val y = sy(st.z)
            val color = when {
                st.selected -> p.amber
                st.loaded -> p.white
                else -> p.dim
            }
            symbol(g, x, y, st, color, p)
            val label = when {
                st.store == null -> "---"
                st.remaining > 1 -> short(st.store) + " x" + st.remaining
                st.remaining == 1 -> short(st.store)
                else -> short(st.store) + " 0"
            }
            val below = n % 2 == 0
            DisplayDraw.text(g, label, x, if (below) y + 24f else y - 22f, 1.15f, color, Align.CENTER)
        }
    }

    private fun symbol(g: GuiGraphics, x: Float, y: Float, st: StoresDisplayState.Station, color: Int, p: DisplayPalette) {
        if (!st.loaded) {
            DisplayDraw.outline(g, (x - 7).roundToInt(), (y - 7).roundToInt(), (x + 7).roundToInt(), (y + 7).roundToInt(), 2, color)
            return
        }
        DisplayDraw.shapes(g) {
            when (st.category) {
                "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE", "COMMAND_GUIDED" -> {
                    // missile: body, nose and fins
                    poly(floatArrayOf(x - 3, y - 12, x + 3, y - 12, x + 3, y + 13, x - 3, y + 13), color)
                    tri(x - 3, y - 12, x + 3, y - 12, x, y - 18, color)
                    tri(x - 3, y + 7, x - 9, y + 14, x - 3, y + 13, color)
                    tri(x + 3, y + 7, x + 9, y + 14, x + 3, y + 13, color)
                }
                "GUN_POD", "ROCKET_POD" -> poly(floatArrayOf(x - 6, y - 12, x + 6, y - 12, x + 6, y + 12, x - 6, y + 12), color)
                else -> {
                    // bomb: fat body with a tail
                    disc(x, y - 1, 7.5f, 0f, color, 20)
                    poly(floatArrayOf(x - 3, y + 5, x + 3, y + 5, x + 6, y + 13, x - 6, y + 13), color)
                }
            }
        }
        if (st.internal) {
            var t = -12f
            while (t < 12f) {
                g.fill((x - 12).roundToInt(), (y + t).roundToInt(), (x - 10).roundToInt(), (y + t + 4).roundToInt(), p.dim)
                g.fill((x + 10).roundToInt(), (y + t).roundToInt(), (x + 12).roundToInt(), (y + t + 4).roundToInt(), p.dim)
                t += 8f
            }
        }
    }

    private fun footer(g: GuiGraphics, s: StoresDisplayState, p: DisplayPalette) {
        val gun = if (s.guns.isEmpty()) "GUN ---" else "GUN " + s.guns.joinToString(" ") { short(it) }
        DisplayDraw.text(g, gun, L, 428f, 1.4f, p.white, Align.LEFT)
        DisplayDraw.text(g, "WT " + s.payloadKg.roundToInt() + " KG", R, 428f, 1.4f, p.cyan, Align.RIGHT)
        val loaded = s.stations.count { it.loaded }
        DisplayDraw.text(g, "STA $loaded/${s.stations.size}", L, 452f, 1.4f, p.cyan, Align.LEFT)
        val selected = s.stations.firstOrNull { it.selected }?.store
        if (selected != null) DisplayDraw.text(g, "SEL " + short(selected), R, 452f, 1.4f, p.amber, Align.RIGHT)
    }

    private fun short(name: String): String {
        val cleaned = name.replace(Regex("\\s*[·(].*$"), "").trim().uppercase()
        return if (cleaned.length <= 9) cleaned else cleaned.substring(0, 9)
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1f
}
