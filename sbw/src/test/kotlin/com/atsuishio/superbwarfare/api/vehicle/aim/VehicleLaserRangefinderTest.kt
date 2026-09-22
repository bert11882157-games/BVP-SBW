package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemKind
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleLaserRangefinderTest {
    private val start = Vec3(0.5, 64.5, 0.5)
    private val end = Vec3(100.5, 64.5, 0.5)

    @Test fun `first solid surface limits return and hides farther vehicle`() {
        val visited = mutableListOf<Int>()
        val terrain = VehicleLaserRangefinder.traceTerrain(start, end) { pos ->
            visited.add(pos.x)
            VehicleLaserRangefinder.TerrainProbe(true,
                if (pos.x == 12 || pos.x == 40) AABB(pos).clip(start, end).orElse(null) else null)
        }
        assertEquals(12.0, terrain.point!!.x)
        assertEquals(12, visited.last())
        assertTrue(VehicleLaserRangefinder.isNearer(start, Vec3(8.0, 64.5, 0.5), terrain.limitSquared))
        assertFalse(VehicleLaserRangefinder.isNearer(start, Vec3(20.0, 64.5, 0.5), terrain.limitSquared))
        assertFalse(VehicleLaserRangefinder.isNearer(start, terrain.point, terrain.limitSquared))
    }

    @Test fun `unknown chunk stops traversal without a false terrain return`() {
        val terrain = VehicleLaserRangefinder.traceTerrain(start, end) { pos ->
            assertTrue(pos.x <= 16, "must not read beyond first unavailable chunk")
            VehicleLaserRangefinder.TerrainProbe(pos.x < 16, null)
        }
        assertNull(terrain.point)
        assertEquals(15.5 * 15.5, terrain.limitSquared, 1e-9)
        assertTrue(VehicleLaserRangefinder.isNearer(start, Vec3(15.0, 64.5, 0.5), terrain.limitSquared))
        assertFalse(VehicleLaserRangefinder.isNearer(start, Vec3(17.0, 64.5, 0.5), terrain.limitSquared))
    }

    @Test fun `empty sky does not fabricate a maximum range and material miss stays a miss`() {
        val terrain = VehicleLaserRangefinder.traceTerrain(start, end) {
            VehicleLaserRangefinder.TerrainProbe(true, null)
        }
        assertNull(terrain.point)
        assertEquals(10000.0, terrain.limitSquared)
        assertFalse(VehicleLaserRangefinder.isNearer(start, null, terrain.limitSquared))
        assertFalse(VehicleLaserRangefinder.isNearer(start, Vec3(Double.NaN, 0.0, 0.0), terrain.limitSquared))
    }

    @Test fun `cannon metadata survives selected missile ammunition without granting machine guns`() {
        assertTrue(VehicleLaserRangefinder.isCannon(WeaponSystemKind.TANK_CANNON, "superbwarfare:atgm"))
        assertTrue(VehicleLaserRangefinder.isCannon(WeaponSystemKind.AUTOCANNON, "superbwarfare:projectile"))
        assertTrue(VehicleLaserRangefinder.isCannon(null, "superbwarfare:small_cannon_shell"))
        assertFalse(VehicleLaserRangefinder.isCannon(WeaponSystemKind.HMG, "superbwarfare:cannon_shell"))
        assertFalse(VehicleLaserRangefinder.isCannon(null, "superbwarfare:atgm"))
    }
}
