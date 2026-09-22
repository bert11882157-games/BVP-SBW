package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleEngineRuntimeTest {
    @Test fun `same config reuses decoded engine and new config replaces its values`() {
        val runtime = VehicleEngineRuntime { throw it }
        val owner = Any()
        val slow = JsonObject().apply { addProperty("MaxBackwardSpeedRate", -0.05) }
        assertTrue(runtime.bind(owner, EngineType.TRACK, slow, null))
        val first = runtime.engine
        assertEquals(-0.05f, (first as EngineInfo.Track).maxBackwardSpeedRate)
        assertFalse(runtime.bind(owner, EngineType.TRACK, slow, first))
        assertSame(first, runtime.engine)
        val fast = JsonObject().apply { addProperty("MaxBackwardSpeedRate", -0.1) }
        assertTrue(runtime.bind(Any(), EngineType.TRACK, fast, first))
        assertNotSame(first, runtime.engine)
        assertEquals(-0.1f, (runtime.engine as EngineInfo.Track).maxBackwardSpeedRate)
    }

    @Test fun `engine type changes retire obsolete implementation`() {
        val runtime = VehicleEngineRuntime { throw it }
        runtime.bind(Any(), EngineType.WHEEL, JsonObject(), null)
        assertTrue(runtime.engine is EngineInfo.Wheel)
        runtime.bind(Any(), EngineType.FIXED, JsonObject(), runtime.engine)
        assertNull(runtime.engine)
    }

    @Test fun `invalid config is attempted once per revision not on every idle tick`() {
        var errors = 0
        val runtime = VehicleEngineRuntime { errors++ }
        val owner = Any()
        val invalid = JsonObject().apply { addProperty("Increment", "broken") }
        repeat(100) { runtime.bind(owner, EngineType.TRACK, invalid, runtime.engine) }
        assertEquals(1, errors)
        assertNull(runtime.engine)
        runtime.bind(Any(), EngineType.TRACK, JsonObject(), null)
        assertNotNull(runtime.engine)
    }
}
