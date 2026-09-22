package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundRotationRefreshTest {
    @Test fun `stationary captured headings retain precision through repeated refreshes`() {
        for ((precise, wire) in listOf(34.277676F to 33.75F, -3.4801567F to -2.8125F)) {
            var rendered = precise
            repeat(8) {
                rendered = GroundRotationRefresh.angle(wire, precise, true)
                assertEquals(precise, rendered)
                // The existing interpolation has nothing to correct after the absolute refresh.
                repeat(10) { rendered += .1F * (precise - rendered) }
                assertEquals(precise, rendered)
            }
        }
    }

    @Test fun `moving and mismatched corrections keep packet rotation`() {
        assertEquals(33.75F, GroundRotationRefresh.angle(33.75F, 34.277676F, false))
        assertEquals(90F, GroundRotationRefresh.angle(90F, 34.277676F, true))
        assertEquals(-90F, GroundRotationRefresh.angle(-90F, -3.4801567F, true))
    }

    @Test fun `positive negative pitch and signed byte wrap match vanilla encoding`() {
        for (angle in listOf(0F, .7F, -.7F, 1.40625F, -1.40625F, 179.9F, -179.9F, 180F, 270F, -270F)) {
            val wire = (angle * 256F / 360F).toInt().toByte().toInt() * 360F / 256F
            assertEquals(angle, GroundRotationRefresh.angle(wire, angle, true))
        }
    }

    @Test fun `unusable precision cannot replace packet data`() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(33.75F, GroundRotationRefresh.angle(33.75F, invalid, true))
        }
        assertTrue(GroundRotationRefresh.angle(Float.NaN, 34.277676F, true).isNaN())
    }
}
