package com.atsuishio.superbwarfare.client.flightdisplay

import net.minecraft.client.gui.GuiGraphics

/**
 * Colours of an electronic display. [LCD] is the full-colour flat panel; [CRT] is a full-colour shadow-mask tube
 * (older glass cockpits): slightly softer phosphor colours on a lifted black, finished with [finish] (faint scan
 * lines, darker toward the tube edge). The tube shape, curvature, bloom and glass reflection are drawn with the screen
 * ([FlightDisplays]).
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
    /** CRT finish over the painted page: faint scan lines and a soft falloff toward the tube edge. */
    fun finish(g: GuiGraphics, size: Int) {
        if (!crt) return
        var y = 0
        while (y < size) {
            g.fill(0, y, size, y + 1, 0x22000000)
            y += 3
        }
        for (k in 0 until 8) {
            val inset = k * 6
            val alpha = (0x30 - k * 6).coerceAtLeast(0)
            if (alpha == 0) break
            val c = alpha shl 24
            g.fill(inset, inset, size - inset, inset + 6, c)
            g.fill(inset, size - inset - 6, size - inset, size - inset, c)
            g.fill(inset, inset + 6, inset + 6, size - inset - 6, c)
            g.fill(size - inset - 6, inset + 6, size - inset, size - inset - 6, c)
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
            white = 0xFFF2F5EA.toInt(),
            black = 0xFF070A09.toInt(),
            cyan = 0xFF46E0EE.toInt(),
            green = 0xFF55F26C.toInt(),
            amber = 0xFFFFB648.toInt(),
            magenta = 0xFFF068D8.toInt(),
            red = 0xFFFF5846.toInt(),
            sky = 0xFF2A62A6.toInt(),
            skyDeep = 0xFF214E88.toInt(),
            ground = 0xFF74502A.toInt(),
            groundDeep = 0xFF5A3E20.toInt(),
            tape = 0xFF2E3238.toInt(),
            tapeDark = 0xFF1C1F24.toInt(),
            frame = 0xFF929AA2.toInt(),
            bezelLine = 0xFF252A30.toInt(),
            background = 0xFF0B0F0D.toInt(),
            dim = 0xFF58626C.toInt(),
        )

        @JvmStatic
        fun of(style: String?): DisplayPalette = if (style.equals("CRT", ignoreCase = true)) CRT else LCD
    }
}
