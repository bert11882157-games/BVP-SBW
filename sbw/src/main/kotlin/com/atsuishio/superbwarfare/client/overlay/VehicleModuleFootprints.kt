package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudKind
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudMarker
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** True-size module outlines on the ground HUD's hull diagram, and their short labels. */
internal object VehicleModuleFootprints {
    /** Half-unit scanlines: the diagram is only 36 units long, whole units would square every module off. */
    private const val SUB = 2

    fun hasFootprint(marker: VehicleModuleHudMarker) =
        marker.footprints.any { it.size >= 6 && it.all(Double::isFinite) }

    /** Area in HUD units, largest first so small modules stay visible on top of big ones. */
    fun area(marker: VehicleModuleHudMarker, hull: VehicleHullRaster.Image): Double =
        marker.footprints.sumOf { flat ->
            val p = project(flat, hull)
            var a = 0.0
            for (i in p.indices step 2) {
                val j = (i + 2) % p.size
                a += p[i] * p[j + 1] - p[j] * p[i + 1]
            }
            kotlin.math.abs(a) / 2
        }

    /** HUD-unit polygon from a model-pixel footprint. */
    fun project(flat: DoubleArray, hull: VehicleHullRaster.Image): DoubleArray =
        DoubleArray(flat.size) { i -> if (i % 2 == 0) hull.hudX(flat[i]) else hull.hudY(flat[i]) }

    /** Centre of the footprint in HUD units, for the label. */
    fun centre(marker: VehicleModuleHudMarker, hull: VehicleHullRaster.Image): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var n = 0
        for (flat in marker.footprints) {
            val p = project(flat, hull)
            for (i in p.indices step 2) { sx += p[i]; sy += p[i + 1]; n++ }
        }
        return if (n == 0) hull.hudX(marker.modelX) to hull.hudY(marker.modelZ) else sx / n to sy / n
    }

    fun draw(g: GuiGraphics, marker: VehicleModuleHudMarker, hull: VehicleHullRaster.Image,
             fill: Int, outline: Int, destroyed: Boolean) {
        val pose = g.pose()
        pose.pushPose()
        pose.scale(1F / SUB, 1F / SUB, 1F)
        for (flat in marker.footprints) {
            if (flat.size < 6 || !flat.all(Double::isFinite)) continue
            val p = project(flat, hull)
            for (i in p.indices) p[i] *= SUB.toDouble()
            // Outline: the full polygon in the outline colour, the fill inset by one sub-unit on top.
            scanFill(g, p, (outline and 0x00FFFFFF) or 0xE0000000.toInt())
            val inset = inset(p, 1.0)
            if (inset != null) scanFill(g, inset, if (destroyed) 0xE0080A0C.toInt() else (fill and 0x00FFFFFF) or 0x90000000.toInt())
        }
        pose.popPose()
    }

    /** Short label for a module identity. */
    fun label(marker: VehicleModuleHudMarker): String {
        val id = marker.id.lowercase()
        return when (marker.kind) {
            VehicleModuleHudKind.ENGINE -> "ENG"
            VehicleModuleHudKind.AMMO -> "AMMO"
            VehicleModuleHudKind.WEAPON -> "GUN"
            VehicleModuleHudKind.TRACK -> if (id.startsWith("left")) "L TRK" else if (id.startsWith("right")) "R TRK" else "TRK"
            VehicleModuleHudKind.WHEEL -> "WHL"
            VehicleModuleHudKind.MODULE -> when {
                "fuel" in id -> "FUEL"
                "optic" in id || "sight" in id -> "OPT"
                "radio" in id -> "RAD"
                "crew" in id || "driver" in id || "gunner" in id || "commander" in id -> "CREW"
                "trans" in id || "gearbox" in id -> "TRANS"
                "turret" in id || "drive" in id -> "TUR"
                "rotor" in id -> "ROTOR"
                else -> id.substringBefore(':').filter(Char::isLetter).take(4).uppercase()
            }
        }
    }

    private fun inset(p: DoubleArray, by: Double): DoubleArray? {
        var cx = 0.0; var cy = 0.0
        val n = p.size / 2
        for (i in 0 until n) { cx += p[2 * i]; cy += p[2 * i + 1] }
        cx /= n; cy /= n
        var extent = 0.0
        for (i in 0 until n) extent = max(extent, max(kotlin.math.abs(p[2 * i] - cx), kotlin.math.abs(p[2 * i + 1] - cy)))
        if (extent <= by * 1.5) return null
        val k = (extent - by) / extent
        return DoubleArray(p.size) { i -> if (i % 2 == 0) cx + (p[i] - cx) * k else cy + (p[i] - cy) * k }
    }

    /** Even-odd scanline fill of one polygon, one row per sub-unit. */
    private fun scanFill(g: GuiGraphics, p: DoubleArray, color: Int) {
        val n = p.size / 2
        var y0 = Double.POSITIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (i in 0 until n) { y0 = min(y0, p[2 * i + 1]); y1 = max(y1, p[2 * i + 1]) }
        if (y1 - y0 > 400) return
        val xs = DoubleArray(n)
        var y = floor(y0).toInt()
        val last = ceil(y1).toInt()
        // At least one row for a sliver thinner than a sub-unit.
        if (last <= y) {
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (i in 0 until n) { lo = min(lo, p[2 * i]); hi = max(hi, p[2 * i]) }
            g.fill(lo.roundToInt(), y, max(hi.roundToInt(), lo.roundToInt() + 1), y + 1, color)
            return
        }
        while (y < last) {
            val yc = y + 0.5
            var count = 0
            for (i in 0 until n) {
                val ax = p[2 * i]; val ay = p[2 * i + 1]
                val bx = p[(2 * i + 2) % p.size]; val by = p[(2 * i + 3) % p.size]
                if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) xs[count++] = ax + (yc - ay) / (by - ay) * (bx - ax)
            }
            xs.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                val a = xs[k].roundToInt(); val b = xs[k + 1].roundToInt()
                g.fill(a, y, max(b, a + 1), y + 1, color)
                k += 2
            }
            y++
        }
    }
}
