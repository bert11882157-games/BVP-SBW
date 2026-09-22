package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarVehicleSimulationPolicyTest {
    private fun resting(age: Int = 100, grounded: Boolean = true, occupied: Boolean = false,
                        engine: Boolean = false, wreck: Boolean = false, burning: Boolean = false,
                        health: Double = 90.0, speedSquared: Double = 0.0) =
        FarVehicleSimulationPolicy.canRest(age, grounded, occupied, engine, wreck, burning, health, 90.0, speedSquared)

    @Test fun `only settled pristine parked aircraft may stop self ticking`() {
        assertTrue(resting(speedSquared = 2.12e-23 * 2.12e-23))
        assertFalse(resting(age = 99))
        assertFalse(resting(grounded = false))
        assertFalse(resting(occupied = true))
        assertFalse(resting(engine = true))
        assertFalse(resting(wreck = true))
        assertFalse(resting(burning = true))
        assertFalse(resting(health = 89.0))
        assertFalse(resting(health = Double.NaN))
        assertFalse(resting(speedSquared = 0.000002))
        assertFalse(resting(speedSquared = Double.NaN))
    }

    @Test fun `native square simulation area has a one chunk margin including negative coordinates`() {
        assertTrue(FarVehicleSimulationPolicy.insideSimulation(13, -13, 0, 0, 12))
        assertFalse(FarVehicleSimulationPolicy.insideSimulation(14, -13, 0, 0, 12))
        assertTrue(FarVehicleSimulationPolicy.insideSimulation(-87, -113, -100, -100, 12))
        assertFalse(FarVehicleSimulationPolicy.insideSimulation(-86, -113, -100, -100, 12))
    }
}
