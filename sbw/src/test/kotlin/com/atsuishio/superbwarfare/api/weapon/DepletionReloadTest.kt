package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DepletionReloadTest {
    private val knots = listOf(
        listOf(0.0, 2.0), listOf(0.10, 4.0), listOf(0.25, 6.0),
        listOf(0.50, 8.0), listOf(0.75, 10.0), listOf(1.0, 12.0),
    )

    private fun s(fired: Int, magazine: Int = 501) = DepletionReload.seconds(knots, magazine, fired)!!

    @Test fun `owner's autocannon points`() {
        // magazine 501: (fired - 1) / 500 hits the knots exactly
        assertEquals(2.0, s(1), 1e-9)
        assertEquals(4.0, s(51), 1e-9)
        assertEquals(6.0, s(126), 1e-9)
        assertEquals(8.0, s(251), 1e-9)
        assertEquals(10.0, s(376), 1e-9)
        assertEquals(12.0, s(501), 1e-9)
    }

    @Test fun `linear between knots and clamped at the ends`() {
        assertEquals(3.0, s(26), 1e-9)       // 5% fired
        assertEquals(2.0, s(0), 1e-9)        // nothing fired (a belt change on a full belt)
        assertEquals(12.0, s(900), 1e-9)
    }

    @Test fun `a 250-round belt`() {
        assertEquals(2.0, DepletionReload.seconds(knots, 250, 1)!!, 1e-9)
        assertEquals(12.0, DepletionReload.seconds(knots, 250, 250)!!, 1e-9)
        val half = DepletionReload.seconds(knots, 250, 125)!!
        assertTrue(half in 7.9..8.0, "half a belt: $half")
    }

    @Test fun `no curve means the fixed reload`() {
        assertNull(DepletionReload.seconds(emptyList(), 250, 100))
        assertTrue(DefaultGunData().depletionReload.isEmpty())
    }

    @Test fun `weapon json decodes the curve`() {
        val json = Json { ignoreUnknownKeys = true }
        val d = json.decodeFromString(DefaultGunData.serializer(),
            """{"Magazine":250,"DepletionReload":[[0.0,2.0],[0.1,4.0],[1.0,12.0]]}""")
        assertEquals(3, d.depletionReload.size)
        assertEquals(12.0, d.depletionReload.last()[1], 1e-9)
    }
}
