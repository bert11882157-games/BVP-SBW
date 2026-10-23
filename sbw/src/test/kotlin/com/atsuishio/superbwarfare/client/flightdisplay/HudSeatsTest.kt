package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HudSeatsTest {
    private fun hud(seat: Int, style: String? = null, frame: String = "VEHICLE_LOCAL_BLOCKS") =
        """{"Schema": 1, "Frame": "$frame", "Seat": $seat, "Center": [0, 2.9, 3.4], "Normal": [0, 0, -1],
            "Up": [0, 1, 0], "Width": 0.12, "Height": 0.11, "Eye": [0, 2.9, 2.8]${if (style != null) ", \"Style\": \"$style\"" else ""}}"""

    private fun parse(json: String): DefaultVehicleResource = Gson().fromJson(json, DefaultVehicleResource::class.java)

    @Test fun `a single HeadUpDisplay still parses and goes live for its own seat only`() {
        val res = parse("""{"HeadUpDisplay": ${hud(0, "GYRO")}}""")
        assertNull(res.headUpDisplays)
        val all = HudSeats.of(res)
        assertEquals(1, all.size)
        assertSame(res.headUpDisplay, all[0])
        assertEquals("GYRO", all[0].style)
        assertEquals(0.12, all[0].width, 1e-9)
        assertSame(res.headUpDisplay, HudSeats.liveFor(0, all))
        assertNull(HudSeats.liveFor(1, all))          // a passenger or gunner sees the still picture, as before
        assertNull(HudSeats.liveFor(-1, all))         // not aboard as crew
    }

    @Test fun `no HUD at all gives nothing`() {
        val res = parse("""{}""")
        assertTrue(HudSeats.of(res).isEmpty())
        assertNull(HudSeats.liveFor(0, HudSeats.of(res)))
    }

    @Test fun `a HeadUpDisplay in another frame is ignored as before`() {
        val res = parse("""{"HeadUpDisplay": ${hud(0, frame = "MODEL_PIXELS")}}""")
        assertTrue(HudSeats.of(res).isEmpty())
    }

    @Test fun `two entries with different seats each go live only in their own seat`() {
        val res = parse("""{"HeadUpDisplay": ${hud(0, "GYRO")}, "HeadUpDisplays": [${hud(1, "REFLECTOR")}]}""")
        val all = HudSeats.of(res)
        assertEquals(2, all.size)
        val pilot = HudSeats.liveFor(0, all)!!
        val gunner = HudSeats.liveFor(1, all)!!
        assertNotSame(pilot, gunner)
        assertEquals(0, pilot.seat); assertEquals("GYRO", pilot.style)
        assertEquals(1, gunner.seat); assertEquals("REFLECTOR", gunner.style)
        assertNull(HudSeats.liveFor(2, all))
        assertNull(HudSeats.liveFor(-1, all))
    }

    @Test fun `HeadUpDisplays alone works and unusable entries are dropped`() {
        val res = parse("""{"HeadUpDisplays": [${hud(1)}, null, ${hud(2, frame = "MODEL_PIXELS")}]}""")
        val all = HudSeats.of(res)
        assertEquals(listOf(1), all.map { it.seat })
        assertNull(HudSeats.liveFor(0, all))
        assertEquals(1, HudSeats.liveFor(1, all)!!.seat)
        assertNull(HudSeats.liveFor(2, all))
    }
}
