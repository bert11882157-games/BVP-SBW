package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class CanopyGlassTintTest {
    private val default = intArrayOf(205, 228, 240)

    private fun glass(tint: IntArray?) = DefaultVehicleResource.CanopyGlassResource().also { it.tint = tint }

    @Test fun absentTintKeepsThePackWideColour() {
        assertArrayEquals(default, CanopyGlass.tintOf(glass(null)))
    }

    @Test fun malformedTintFallsBackToThePackWideColour() {
        assertArrayEquals(default, CanopyGlass.tintOf(glass(intArrayOf(255, 200))))
        assertArrayEquals(default, CanopyGlass.tintOf(glass(intArrayOf(255, 200, 80, 10))))
        assertArrayEquals(default, CanopyGlass.tintOf(glass(intArrayOf(300, 200, 80))))
        assertArrayEquals(default, CanopyGlass.tintOf(glass(intArrayOf(-1, 200, 80))))
    }

    @Test fun validTintIsUsed() {
        assertArrayEquals(intArrayOf(240, 190, 70), CanopyGlass.tintOf(glass(intArrayOf(240, 190, 70))))
    }
}
