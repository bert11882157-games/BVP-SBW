package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BallisticSyncTest {
    private val gravity = 0.05
    private val step: (Vec3) -> Vec3 = { NominalProjectileMotion.afterStep(it, gravity) }
    private val unstep: (Vec3) -> Vec3 = { it.add(0.0, gravity, 0.0) }

    private fun fly(position: Vec3, velocity: Vec3, steps: Int): Pair<Vec3, Vec3> {
        var p = position
        var v = velocity
        repeat(steps) { p = p.add(v); v = step(v) }
        return p to v
    }

    @Test fun `undisturbed step matches the client prediction and any departure is published`() {
        val start = Vec3(10.0, 80.0, -4.0)
        val velocity = Vec3(30.0, 1.5, -12.0)
        val (end, next) = fly(start, velocity, 1)
        assertFalse(BallisticSync.deviates(start, velocity, end, next, step(velocity)))
        // Ricochet: same position, new heading.
        assertTrue(BallisticSync.deviates(start, velocity, end, Vec3(-next.x, next.y, next.z), step(velocity)))
        // Fluid drag after the move.
        assertTrue(BallisticSync.deviates(start, velocity, end, next.scale(0.75), step(velocity)))
        // Position moved to an impact point.
        assertTrue(BallisticSync.deviates(start, velocity, end.subtract(velocity.scale(0.5)), next, step(velocity)))
        assertTrue(BallisticSync.deviates(start, velocity, Vec3(Double.NaN, 0.0, 0.0), next, step(velocity)))
    }

    @Test fun `aligned state reproduces the client step in both directions`() {
        val origin = Vec3(0.0, 100.0, 0.0)
        val launch = Vec3(40.0, 2.0, 5.0)
        val (at20, v20) = fly(origin, launch, 20)
        val (at23, v23) = fly(origin, launch, 23)
        val ahead = BallisticSync.align(20, 23, at20, v20, step, unstep)!!
        assertTrue(ahead.first.distanceTo(at23) < 1.0E-9)
        assertTrue(ahead.second.distanceTo(v23) < 1.0E-9)
        val behind = BallisticSync.align(23, 20, at23, v23, step, unstep)!!
        assertTrue(behind.first.distanceTo(at20) < 1.0E-9)
        assertTrue(behind.second.distanceTo(v20) < 1.0E-9)
        assertEquals(at20, BallisticSync.align(20, 20, at20, v20, step, unstep)!!.first)
    }

    @Test fun `alignment refuses unbounded or invalid gaps`() {
        val p = Vec3(0.0, 64.0, 0.0)
        val v = Vec3(1.0, 0.0, 0.0)
        assertNull(BallisticSync.align(0, BallisticSync.MAX_ALIGNED_STEPS + 1, p, v, step, unstep))
        assertNull(BallisticSync.align(BallisticSync.MAX_ALIGNED_STEPS + 1, 0, p, v, step, unstep))
        assertNull(BallisticSync.align(-1, 3, p, v, step, unstep))
        assertNull(BallisticSync.align(0, 3, Vec3(Double.NaN, 0.0, 0.0), v, step, unstep))
    }

    @Test fun `small correction is spread over blend ticks and sums to the error`() {
        val correction = BallisticSync.Correction()
        val error = Vec3(0.9, -0.3, 0.6)
        assertTrue(correction.offer(error, 30.0))
        var applied = Vec3.ZERO
        var ticks = 0
        while (true) {
            val share = correction.next() ?: break
            assertTrue(share.length() <= error.length() / BallisticSync.BLEND_TICKS + 1.0E-9)
            applied = applied.add(share)
            ticks++
        }
        assertEquals(BallisticSync.BLEND_TICKS, ticks)
        assertTrue(applied.distanceTo(error) < 1.0E-9)
        assertFalse(correction.pending())
    }

    @Test fun `large or invalid corrections are applied directly and newer states replace older ones`() {
        val correction = BallisticSync.Correction()
        assertFalse(correction.offer(Vec3(BallisticSync.snapDistance(20.0) + 0.1, 0.0, 0.0), 20.0))
        assertFalse(correction.pending())
        assertFalse(correction.offer(Vec3(Double.NaN, 0.0, 0.0), 20.0))
        assertTrue(correction.offer(Vec3(3.0, 0.0, 0.0), 20.0))
        correction.next()
        assertTrue(correction.offer(Vec3(0.0, 0.3, 0.0), 20.0))
        var total = Vec3.ZERO
        while (true) total = total.add(correction.next() ?: break)
        assertTrue(total.distanceTo(Vec3(0.0, 0.3, 0.0)) < 1.0E-9, "Superseded remainder must not be applied")
        assertTrue(correction.offer(Vec3(1.0E-6, 0.0, 0.0), 20.0))
        assertNull(correction.next(), "Negligible differences need no blend")
    }

    @Test fun `unaligned tracker positions only correct a gross divergence`() {
        val velocity = Vec3(40.0, 0.0, 0.0)
        // Several ticks of timing difference along the line of flight, plus drop, is not an error.
        assertFalse(BallisticSync.grossDivergence(Vec3(-40.0 * 30, -20.0, 0.0), velocity))
        assertFalse(BallisticSync.grossDivergence(Vec3(0.0, 3.0, 0.0), velocity))
        assertTrue(BallisticSync.grossDivergence(Vec3(0.0, 0.0, 40.0 * BallisticSync.MAX_ALIGNED_STEPS + 200.0), velocity))
        assertTrue(BallisticSync.grossDivergence(Vec3(0.0, 500.0, 0.0), Vec3.ZERO))
        assertFalse(BallisticSync.grossDivergence(Vec3(Double.NaN, 0.0, 0.0), velocity))
    }

    @Test fun `fast throwable air step inverse stays within float cancellation error`() {
        val throwableStep: (Vec3) -> Vec3 = { NominalProjectileMotion.afterFastThrowableAirStep(it, gravity) }
        val dragProduct = 0.99f.toDouble() * (1f / 0.99f).toDouble()
        val throwableUnstep: (Vec3) -> Vec3 = { it.add(0.0, gravity, 0.0).scale(1.0 / dragProduct) }
        var p = Vec3(0.0, 90.0, 0.0)
        var v = Vec3(25.0, 1.0, -8.0)
        val p0 = p; val v0 = v
        repeat(5) { p = p.add(v); v = throwableStep(v) }
        val rewound = BallisticSync.align(5, 0, p, v, throwableStep, throwableUnstep)!!
        assertTrue(rewound.first.distanceTo(p0) < 1.0E-6)
        assertTrue(rewound.second.distanceTo(v0) < 1.0E-7)
    }
}
