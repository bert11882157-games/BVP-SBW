package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AircraftBombFlightTest {
    @Test fun releaseRetainsAircraftVelocityAndNormalBombCarriesItForward() {
        val inherited = Vec3(1.8, 0.2, 0.5)
        val released = AircraftBombFlight.initialMotion(inherited)
        assertEquals(inherited.x, released.x, 1e-9)
        assertEquals(inherited.z, released.z, 1e-9)
        assertEquals(0.16, released.y, 1e-9)
        val normal = (0 until 60).fold(released) { motion, _ ->
            AircraftBombFlight.applyHorizontalDrag(motion, 1.0)
        }
        val retarded = (0 until 60).fold(released) { motion, _ ->
            AircraftBombFlight.applyHorizontalDrag(motion, 5.0)
        }
        assertTrue(normal.x > 1.5, "a normal bomb must retain most aircraft speed for three seconds")
        assertTrue(retarded.x < 1.0, "SnakeEye retarder must lose substantially more forward speed")
    }
}
