package com.atsuishio.superbwarfare.client.flightdisplay

import net.minecraft.client.gui.GuiGraphics

/**
 * Colours of an electronic display. [LCD] is the full-colour flat panel; [CRT] is a monochrome green-phosphor tube
 * (older glass cockpits), drawn with the same symbology in shades of green and finished with [finish] (scan lines,
 * darkened rounded corners).
 */
internal class DisplayPalette(
    val crt: Boolean,
    val white: Int,
    val black: Int,
    val cyan: Int,
    val green: Int,
    val amber: Int,
    val magenta: Int,
    val red: Int,
    val sky: Int,
    val skyDeep: Int,
    val ground: Int,
    val groundDeep: Int,
    val tape: Int,
    val tapeDark: Int,
    val frame: Int,
    val bezelLine: Int,
    val background: Int,
    val dim: Int,
) {
    /** CRT finish over the painted page: scan lines and a vignette toward the rounded tube corners. */
    fun finish(g: GuiGraphics, size: Int) {
        if (!crt) return
        var y = 0
        while (y < size) {
            g.fill(0, y, size, y + 1, 0x40000000)
            y += 3
        }
        // Vignette: nested translucent frames, darkest at the edge.
        for (k in 0 until 10) {
            val inset = k * 5
            val alpha = (0x58 - k * 9).coerceAtLeast(0)
            if (alpha == 0) break
            val c = alpha shl 24
            g.fill(inset, inset, size - inset, inset + 5, c)
            g.fill(inset, size - inset - 5, size - inset, size - inset, c)
            g.fill(inset, inset + 5, inset + 5, size - inset - 5, c)
            g.fill(size - inset - 5, inset + 5, size - inset, size - inset - 5, c)
        }
        // Rounded tube corners.
        val r = 40
        for (i in 0 until r) {
            val w = r - kotlin.math.sqrt((r * r - (r - i) * (r - i)).toDouble()).toInt()
            if (w <= 0) continue
            g.fill(0, i, w, i + 1, 0xFF000000.toInt())
            g.fill(size - w, i, size, i + 1, 0xFF000000.toInt())
            g.fill(0, size - i - 1, w, size - i, 0xFF000000.toInt())
            g.fill(size - w, size - i - 1, size, size - i, 0xFF000000.toInt())
        }
    }

    companion object {
        @JvmField
        val LCD = DisplayPalette(
            crt = false,
            white = 0xFFFFFFFF.toInt(),
            black = 0xFF000000.toInt(),
            cyan = 0xFF2CE6FF.toInt(),
            green = 0xFF3DFF5A.toInt(),
            amber = 0xFFFFB21C.toInt(),
            magenta = 0xFFFF4FE0.toInt(),
            red = 0xFFFF3B30.toInt(),
            sky = 0xFF1F6FD1.toInt(),
            skyDeep = 0xFF1856A8.toInt(),
            ground = 0xFF7A4A1E.toInt(),
            groundDeep = 0xFF5E3915.toInt(),
            tape = 0xFF3A3F47.toInt(),
            tapeDark = 0xFF22262C.toInt(),
            frame = 0xFF9AA3AD.toInt(),
            bezelLine = 0xFF2A2F36.toInt(),
            background = 0xFF06080B.toInt(),
            dim = 0xFF5A6470.toInt(),
        )

        @JvmField
        val CRT = DisplayPalette(
            crt = true,
            white = 0xFF8CFF98.toInt(),
            black = 0xFF010602.toInt(),
            cyan = 0xFF5CE872.toInt(),
            green = 0xFFB4FFBC.toInt(),
            amber = 0xFFC8FFC4.toInt(),
            magenta = 0xFF78F088.toInt(),
            red = 0xFFD8FFD8.toInt(),
            sky = 0xFF0B3F16.toInt(),
            skyDeep = 0xFF083011.toInt(),
            ground = 0xFF031407.toInt(),
            groundDeep = 0xFF020D05.toInt(),
            tape = 0xFF06240C.toInt(),
            tapeDark = 0xFF041A08.toInt(),
            frame = 0xFF3FA04F.toInt(),
            bezelLine = 0xFF0E3A16.toInt(),
            background = 0xFF010502.toInt(),
            dim = 0xFF2A7A38.toInt(),
        )

        @JvmStatic
        fun of(style: String?): DisplayPalette = if (style.equals("CRT", ignoreCase = true)) CRT else LCD
    }
}
