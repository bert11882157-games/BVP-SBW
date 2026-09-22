package com.atsuishio.superbwarfare.client.overlay

/** GUI units, not physical pixels: GUI scale changes text and icons together. */
data class GroundHudLayout(val width: Int, val height: Int, val systemCount: Int) {
    companion object {
        @JvmStatic
        fun passengerRowY(height: Int, reverseIndex: Int): Int =
            height - 82 - (height * 0.18F).toInt().coerceIn(52,84) - reverseIndex * 12

        /** Passenger names end at x114; reserve their column only on intersecting rows. */
        @JvmStatic
        fun alertLeft(height: Int, passengerCount: Int, top: Int, bottom: Int, fontHeight: Int): Int {
            if (passengerCount == 0) return 12
            val rosterTop = passengerRowY(height, passengerCount - 1)
            val rosterBottom = passengerRowY(height, 0) + fontHeight
            return if (top < rosterBottom && bottom > rosterTop) 124 else 12
        }
    }

    val slotWidth = 40
    val slotHeight = 38
    val panelRight = width - 12
    val shellWidth = (width - 136).coerceIn(64,220)
    val perRow = ((shellWidth + 4) / 44).coerceIn(1,6)
    val rows = ((systemCount.coerceAtLeast(1) + perRow - 1) / perRow)
    val firstRowTop = height - 58 - (rows - 1) * 76
    val amberLineY = firstRowTop - 47
    val shellCenterX = panelRight - shellWidth / 2
    fun slotLeft(index: Int): Int {
        val count=minOf(perRow,systemCount-index/perRow*perRow)
        return panelRight - count * 44 + 4 + index % perRow * 44
    }
    fun slotTop(index: Int) = firstRowTop + index/perRow*76
}
