package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.network.message.receive.RicochetTracerMessage
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Owner 2026-09-30: a ricochet eats the shot; clients see a cosmetic tracer at half the reflected speed. */
class RicochetTracerTest {
    @Test fun `the cosmetic tracer flies at half the reflected speed`() {
        val v = RicochetTracer.tracerVelocity(Vec3(20.0, 4.0, -10.0))
        assertEquals(10.0, v.x, 1e-9)
        assertEquals(2.0, v.y, 1e-9)
        assertEquals(-5.0, v.z, 1e-9)
        // capped at the packet's speed bound
        assertEquals(RicochetTracerMessage.MAX_SPEED, RicochetTracer.tracerVelocity(Vec3(1000.0, 0.0, 0.0)).length(), 1e-9)
    }

    @Test fun `the tracer message rejects bad values`() {
        fun msg(v: Vec3 = Vec3(1.0, 0.0, 0.0), r: Float = 1f, width: Float = 0.3f) =
            RicochetTracerMessage(Vec3.ZERO, v, r, 0.5f, 0.2f, width, 0.05f)
        assertTrue(msg().valid())
        assertFalse(msg(v = Vec3(Double.NaN, 0.0, 0.0)).valid())
        assertFalse(msg(v = Vec3(100.0, 0.0, 0.0)).valid())
        assertFalse(msg(r = 2f).valid())
        assertFalse(msg(width = 0f).valid())
    }
}
