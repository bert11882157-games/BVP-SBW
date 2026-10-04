package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

/**
 * Owner 2026-09-30: TV missiles boost to just faster than their carrier, gently, and steer smoothly.
 * Owner 2026-10-02: they were too slow; a dive speeds them up decently (not extremely), a climb costs some speed,
 * and they cap out at 400 km/h (5.556 blocks/tick).
 */
class TvMissileCruiseTest {
    private val cap = GuidedMissileGuidance.TV_SPEED_CAP
    private fun kmh(blocksPerTick: Double) = blocksPerTick * 72.0

    private fun fly(start: Double, target: Double, pathDegrees: Double, ticks: Int, burning: Boolean = true): Double {
        var v = start
        val sine = sin(Math.toRadians(pathDegrees))
        repeat(ticks) { v = GuidedMissileGuidance.tvCruiseStep(v, target, burning, sine) }
        return v
    }

    @Test fun `the cap is 400 km per hour`() {
        assertEquals(400.0, kmh(cap), 1e-9)
    }

    @Test fun `cruise speed is a little above the carrier, at least 300 km per hour and never above the cap`() {
        // a 300 km/h carrier: 300 + 90 clipped to the 360 km/h level-cruise ceiling
        assertEquals(GuidedMissileGuidance.TV_CRUISE_MAX_SPEED, GuidedMissileGuidance.tvCruiseSpeed(300.0 / 72, 10.0), 1e-9)
        // a helicopter: the 300 km/h floor
        assertEquals(GuidedMissileGuidance.TV_CRUISE_MIN_SPEED, GuidedMissileGuidance.tvCruiseSpeed(0.5, 10.0), 1e-9)
        // a 380 km/h carrier: never slower than the carrier was
        assertEquals(380.0 / 72, GuidedMissileGuidance.tvCruiseSpeed(380.0 / 72, 10.0), 1e-9)
        // a fast jet: the cap
        assertEquals(cap, GuidedMissileGuidance.tvCruiseSpeed(10.0, 10.0), 1e-9)
        // the profile's MaxSpeed above the carrier still bounds it
        assertEquals(2.5, GuidedMissileGuidance.tvCruiseSpeed(2.0, 0.5), 1e-9)
    }

    @Test fun `level flight eases to cruise and holds it`() {
        val target = GuidedMissileGuidance.tvCruiseSpeed(300.0 / 72, 10.0)
        var v = 300.0 / 72
        var ticks = 0
        while (v < target - 0.02 && ticks < 400) { v = GuidedMissileGuidance.tvCruiseStep(v, target, true, 0.0); ticks++ }
        assertTrue(ticks in 30..200, "reaches cruise in $ticks ticks (decent, not a snap)")
        assertEquals(target, fly(v, target, 0.0, 400), 0.02)
    }

    @Test fun `a dive gains speed decently and stops at the cap`() {
        val target = GuidedMissileGuidance.tvCruiseSpeed(300.0 / 72, 10.0)
        val start = target
        // the first second of a 30 degree dive: 15-45 km/h faster (decent, not extreme)
        val gained = kmh(fly(start, target, -30.0, 20)) - kmh(start)
        assertTrue(gained in 15.0..45.0, "30 deg dive gains $gained km/h in 1 s")
        // a steeper dive gains more
        assertTrue(fly(start, target, -60.0, 20) > fly(start, target, -30.0, 20))
        // and however long or steep, never past 400 km/h
        for (path in listOf(-15.0, -45.0, -90.0)) {
            var v = start
            repeat(600) {
                v = GuidedMissileGuidance.tvCruiseStep(v, target, true, sin(Math.toRadians(path)))
                assertTrue(v <= cap + 1e-9, "dive $path: $v")
            }
            assertEquals(cap, v, 1e-6, "dive $path ends at the cap")
        }
        // even burnt out, a dive still gains speed
        assertTrue(fly(start, target, -45.0, 20, burning = false) > start)
    }

    @Test fun `a climb costs some speed but it keeps flying`() {
        val target = GuidedMissileGuidance.tvCruiseSpeed(300.0 / 72, 10.0)
        val climb20 = kmh(fly(target, target, 20.0, 200))
        val climb45 = kmh(fly(target, target, 45.0, 200))
        val cruise = kmh(target)
        assertTrue(climb20 in cruise - 60.0..cruise - 10.0, "20 deg climb settles at $climb20 km/h (cruise $cruise)")
        assertTrue(climb45 < climb20 && climb45 > 200.0, "45 deg climb settles at $climb45 km/h")
        // straight up and burnt out it slows hard, but never stops in the air
        assertTrue(fly(target, target, 90.0, 400, burning = false) >= GuidedMissileGuidance.TV_MIN_SPEED - 1e-9)
    }

    @Test fun `released faster than the cap it bleeds down quickly`() {
        val target = GuidedMissileGuidance.tvCruiseSpeed(8.0, 10.0)
        assertEquals(cap, target, 1e-9)
        var v = 8.0
        v = GuidedMissileGuidance.tvCruiseStep(v, target, true, 0.0)
        assertTrue(v < 8.0 && v > cap, "first tick $v")
        v = fly(v, target, -30.0, 30)
        assertEquals(cap, v, 0.02, "after 1.5 s: ${kmh(v)} km/h")
    }

    @Test fun `steering closes a share of the angle`() {
        val eased = GuidedMissileGuidance.easeToward(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), 0.25)
        val angle = Math.toDegrees(kotlin.math.atan2(eased.z, eased.x))
        assertTrue(angle in 10.0..25.0, "eased angle $angle")
        assertEquals(1.0, eased.length(), 1e-9)
    }
}
