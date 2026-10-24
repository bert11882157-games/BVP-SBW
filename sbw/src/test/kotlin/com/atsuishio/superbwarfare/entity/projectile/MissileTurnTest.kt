package com.atsuishio.superbwarfare.entity.projectile

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos

class MissileTurnTest {
    private fun degrees(a: Vec3, b: Vec3) = Math.toDegrees(acos(a.normalize().dot(b.normalize()).coerceIn(-1.0, 1.0)))

    @Test fun `turn is bounded by the rate as one rotation, diagonal included`() {
        val facing = Vec3(0.0, 0.0, 1.0)
        val target = Vec3(1.0, 1.0, 0.2)
        val turned = MissileTurn.rotateToward(facing, target, 6.0)!!
        assertEquals(6.0, degrees(facing, turned), 1.0E-9)
        // The turn stays in the plane of the two directions.
        assertEquals(0.0, facing.cross(target).normalize().dot(turned), 1.0E-9)
        assertEquals(1.0, turned.length(), 1.0E-12)
    }

    @Test fun `small remaining angles close by 95 percent per tick`() {
        val facing = Vec3(0.0, 0.0, 1.0)
        val target = Vec3(0.02, 0.0, 1.0)
        val before = degrees(facing, target)
        val turned = MissileTurn.rotateToward(facing, target, 15.0)!!
        assertEquals(before * 0.05, degrees(turned, target), 1.0E-9)
    }

    @Test fun `a dive through vertical never whips the nose around`() {
        // A top-attack plunge: nose 1 degree off straight down, target straight down and slightly behind.
        var facing = Vec3(0.0, -1.0, 0.0175).normalize()
        val target = Vec3(-0.001, -1.0, -0.0005)
        repeat(20) {
            val next = MissileTurn.rotateToward(facing, target, 6.0)!!
            assertTrue(degrees(facing, next) <= 6.0 + 1.0E-9)
            facing = next
        }
        assertTrue(degrees(facing, target) < 0.01)
    }

    @Test fun `exact reversal and empty inputs`() {
        val reversed = MissileTurn.rotateToward(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0), 10.0)!!
        assertEquals(10.0, degrees(Vec3(0.0, 0.0, 1.0), reversed), 1.0E-9)
        assertNull(MissileTurn.rotateToward(Vec3.ZERO, Vec3(0.0, 0.0, 1.0), 10.0))
        assertNull(MissileTurn.rotateToward(Vec3(0.0, 0.0, 1.0), Vec3(Double.NaN, 0.0, 1.0), 10.0))
        assertNull(MissileTurn.rotateToward(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0), 0.0))
    }
}
