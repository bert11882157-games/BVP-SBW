package com.atsuishio.superbwarfare.client.aircraft

import kotlin.math.pow

/** Keep physical-station controls separate even when projected pylons nearly overlap. */
internal object AircraftLoadoutLayout {
    const val BUTTON_WIDTH = 24
    data class Anchor(val id: String, val name: String, val x: Int, val y: Int)
    data class Placement(val anchor: Anchor, val x: Int, val y: Int)
    fun place(anchors: List<Anchor>, width: Int, height: Int, bayRows: Int): List<Placement> {
        val right = (width - BUTTON_WIDTH - 2).coerceAtLeast(2)
        val bottom = (height - 40 - bayRows * 23).coerceAtLeast(52)
        val assigned = ArrayList<Placement>()
        for(anchor in anchors.sortedBy { it.x }) {
            val preferred = (anchor.x - BUTTON_WIDTH / 2).coerceIn(2,right) to (anchor.y + 12).coerceIn(52,bottom)
            val candidates = listOf(preferred) + (52..bottom step 24).flatMap { y ->
                (2..right step BUTTON_WIDTH + 4).map { x -> x to y }
            }
            val position = candidates.filter { (x,y) -> assigned.none {
                x < it.x + BUTTON_WIDTH + 3 && x + BUTTON_WIDTH + 3 > it.x && y < it.y + 23 && y + 23 > it.y
            } }.minByOrNull { (x,y) -> (x-preferred.first).toDouble().pow(2) + (y-preferred.second).toDouble().pow(2) }
                ?: preferred
            assigned += Placement(anchor,position.first,position.second)
        }
        return assigned
    }
}
