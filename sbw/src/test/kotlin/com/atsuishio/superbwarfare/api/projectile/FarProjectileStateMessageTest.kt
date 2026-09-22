package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.network.message.receive.FarProjectileStateMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarProjectileStateMessageTest {
    private val pause = FarProjectileStateMessage(47, "9e974732-8a41-46fb-9775-7190350d161f", true,
        4096.5, 140.0, 4256.5, 10.0, 0.0, 0.0)

    @Test fun `pause and resume preserve identity position and original velocity through codec`() {
        for (packet in listOf(pause, pause.copy(paused = false))) {
            assertTrue(packet.valid())
            assertEquals(packet, Json.decodeFromString<FarProjectileStateMessage>(Json.encodeToString(packet)))
        }
    }

    @Test fun `invalid state cannot introduce nonfinite or world bound positions`() {
        assertFalse(pause.copy(x = Double.NaN).valid())
        assertFalse(pause.copy(vz = Double.POSITIVE_INFINITY).valid())
        assertFalse(pause.copy(z = 30_000_001.0).valid())
        assertFalse(pause.copy(uuid = "recycled-id").valid())
    }
}
