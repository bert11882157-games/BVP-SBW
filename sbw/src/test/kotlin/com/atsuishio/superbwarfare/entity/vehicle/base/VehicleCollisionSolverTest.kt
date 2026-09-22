package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleCollisionSolverTest {
    private val box = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    @Test fun `zero movement does not query collision geometry`() {
        assertEquals(Vec3.ZERO, VehicleCollisionSolver.collide(Vec3.ZERO, box, true, 1.0) { _, _ ->
            error("zero movement must not resolve shapes")
        })
    }

    @Test fun `unobstructed motion uses one solver query`() {
        var calls = 0
        val requested = Vec3(0.4, -0.1, 0.2)
        assertEquals(requested, VehicleCollisionSolver.collide(requested, box, true, 1.0) { v, _ ->
            calls++; v
        })
        assertEquals(1, calls)
    }

    @Test fun `successful step authority cannot leak into another call`() {
        val requested = Vec3(1.0, 0.0, 0.0)
        fun attempt(grounded: Boolean): Vec3 {
            var calls = 0
            return VehicleCollisionSolver.collide(requested, box, grounded, 0.5) { v, _ ->
                calls++
                if (calls == 1) Vec3.ZERO else v
            }
        }
        assertEquals(requested, attempt(true))
        assertEquals(Vec3.ZERO, attempt(false))
        assertEquals(requested, attempt(true))
    }

    @Test fun `exception cannot retain forced stepping authority`() {
        assertThrows(IllegalStateException::class.java) {
            VehicleCollisionSolver.collide(Vec3(1.0, 0.0, 0.0), box, true, 0.5) { _, _ -> error("fixture") }
        }
        var calls = 0
        assertEquals(Vec3.ZERO, VehicleCollisionSolver.collide(Vec3(1.0, 0.0, 0.0), box, false, 0.5) { _, _ ->
            calls++; Vec3.ZERO
        })
        assertEquals(1, calls)
    }
}
