package com.atsuishio.superbwarfare.tools.blast

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sqrt

class BlastModelTest {
    private val p = BlastParameters.DEFAULT

    @Test fun `hopkinson cranz radii match the owner's reference charges`() {
        fun check(kg: Double, fireball: Double, severe: Double, moderate: Double?) {
            val r = BlastModel.radii(kg, p)
            assertEquals(fireball, r.fireball, 0.01, "fireball $kg kg")
            assertEquals(severe, r.severe, 0.01, "severe $kg kg")
            moderate?.let { assertEquals(it, r.moderate, 0.01, "moderate $kg kg") }
        }
        check(1.0, 0.5, 1.8, 3.5)
        check(5.0, 0.86, 3.08, null)
        check(100.0, 2.32, 8.36, null)
        check(0.1, 0.23, 0.84, null)
        assertEquals(35.0, BlastModel.radii(1000.0, p).moderate, 1e-9)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(BlastRadii(0.0, 0.0, 0.0), BlastModel.radii(bad, p))
            assertFalse(BlastModel.valid(bad))
        }
    }

    @Test fun `zones are classified by scaled distance`() {
        val r = BlastModel.radii(1.0, p)
        assertEquals(BlastZone.FIREBALL, BlastModel.classify(0.0, r))
        assertEquals(BlastZone.FIREBALL, BlastModel.classify(0.5, r))
        assertEquals(BlastZone.SEVERE, BlastModel.classify(1.0, r))
        assertEquals(BlastZone.SEVERE, BlastModel.classify(1.8, r))
        assertEquals(BlastZone.MODERATE, BlastModel.classify(3.0, r))
        assertEquals(BlastZone.OUTSIDE, BlastModel.classify(3.6, r))
        assertEquals(BlastZone.OUTSIDE, BlastModel.classify(Double.NaN, r))
        assertEquals(BlastZone.OUTSIDE, BlastModel.classify(-1.0, r))
    }

    @Test fun `infantry damage is heavy near the centre lighter outward and reduced by cover`() {
        val r = BlastModel.radii(5.0, p)
        assertEquals(40.0, BlastModel.infantryDamage(0.0, 1.0, r, p), 1e-9)
        assertEquals(40.0, BlastModel.infantryDamage(r.fireball, 1.0, r, p), 1e-9)
        assertEquals(0.0, BlastModel.infantryDamage(r.severe, 1.0, r, p), 1e-9)
        assertEquals(0.0, BlastModel.infantryDamage(r.severe + 0.01, 1.0, r, p), 1e-9)
        var previous = Double.MAX_VALUE
        var d = r.fireball
        while (d <= r.severe) {
            val damage = BlastModel.infantryDamage(d, 1.0, r, p)
            assertTrue(damage <= previous + 1e-12, "monotonic at $d")
            previous = damage
            d += 0.05
        }
        val middle = (r.fireball + r.severe) / 2
        // Heavy near the centre, lighter farther away: the midpoint keeps (0.25 + 0.5) / 2 of the damage.
        assertEquals(40.0 * 0.375, BlastModel.infantryDamage(middle, 1.0, r, p), 1e-9)
        // Full cover still passes the floor; partial cover passes the visible fraction.
        assertEquals(4.0, BlastModel.infantryDamage(0.0, 0.0, r, p), 1e-9)
        assertEquals(20.0, BlastModel.infantryDamage(0.0, 0.5, r, p), 1e-9)
        assertEquals(4.0, BlastModel.infantryDamage(0.0, Double.NaN, r, p), 1e-9)
        assertEquals(0.1, BlastModel.exposure(-3.0, 0.1), 1e-12)
        assertEquals(1.0, BlastModel.exposure(7.0, 0.1), 1e-12)
        val softer = p.copy(softVehicleCentreDamage = 25.0)
        assertEquals(25.0, BlastModel.softVehicleDamage(0.0, 1.0, r, softer), 1e-9)
    }

    @Test fun `vehicle true damage is three times the charge inside 1_4 fireball radii from 25 kg`() {
        val r100 = BlastModel.radii(100.0, p)
        assertEquals(1.4 * r100.fireball, BlastModel.vehicleTrueDamageRadius(r100, p), 1e-12)
        assertEquals(300.0, BlastModel.vehicleTrueDamage(100.0, 0.0, r100, p), 1e-9)
        assertEquals(300.0, BlastModel.vehicleTrueDamage(100.0, 1.4 * r100.fireball, r100, p), 1e-9)
        assertEquals(0.0, BlastModel.vehicleTrueDamage(100.0, 1.4 * r100.fireball + 1e-6, r100, p), 1e-9)
        val r25 = BlastModel.radii(25.0, p)
        assertEquals(75.0, BlastModel.vehicleTrueDamage(25.0, 0.0, r25, p), 1e-9)
        val r24 = BlastModel.radii(24.99, p)
        assertEquals(0.0, BlastModel.vehicleTrueDamage(24.99, 0.0, r24, p), 1e-9)
        assertFalse(BlastModel.damagesVehicles(24.99, p))
        assertTrue(BlastModel.damagesVehicles(25.0, p))
        assertEquals(0.0, BlastModel.vehicleTrueDamage(100.0, Double.NaN, r100, p))
        assertEquals(3000.0, BlastModel.vehicleTrueDamageInCylinder(1000.0, p), 1e-9)
        assertEquals(0.0, BlastModel.vehicleTrueDamageInCylinder(10.0, p), 1e-9)
    }

    @Test fun `shockwave starts at one tonne`() {
        assertFalse(BlastModel.producesShockwave(999.9, p))
        assertTrue(BlastModel.producesShockwave(1000.0, p))
        assertFalse(BlastModel.producesShockwave(Double.NaN, p))
        assertEquals(1500, BlastModel.shockwaveParticleCount(35.0, 1500))
        assertEquals(64, BlastModel.shockwaveParticleCount(1.0, 1500))
        assertEquals(10, BlastModel.shockwaveParticleCount(35.0, 10))
        assertEquals(0, BlastModel.shockwaveParticleCount(35.0, 0))
        assertEquals(2.0, BlastModel.shockwaveRadiusAt(0.0, 2.0, 30.0), 1e-12)
        assertEquals(30.0, BlastModel.shockwaveRadiusAt(1.0, 2.0, 30.0), 1e-12)
        assertTrue(BlastModel.shockwaveRadiusAt(0.5, 2.0, 30.0) > 16.0, "front decelerates")
        val out = DoubleArray(3)
        for (i in 0 until 200) {
            BlastModel.hemisphereDirection(i, 200, 1.3, out)
            assertEquals(1.0, sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]), 1e-9)
            assertTrue(out[1] >= 0.0)
        }
    }

    @Test fun `penetrator cylinder carries the fireball volume forward`() {
        val rf = BlastModel.radii(1000.0, p).fireball
        val cylinder = BlastModel.penetratorCylinder(10.0, 64.0, -3.0, 0.0, -2.0, 0.0, rf, p)!!
        assertEquals(0.5 * rf, cylinder.radius, 1e-12)
        assertEquals(16.0 / 3.0 * rf, cylinder.length, 1e-9)
        assertEquals(4.0 / 3.0 * PI * rf * rf * rf, cylinder.volume, 1e-6)
        assertEquals(-1.0, cylinder.dy, 1e-12)
        // Inside: along the axis below the detonation point, within the radius.
        assertTrue(cylinder.contains(10.0, 64.0 - 0.5 * cylinder.length, -3.0 + 0.9 * cylinder.radius))
        assertTrue(cylinder.contains(10.0, 64.0 - 0.999 * cylinder.length, -3.0))
        // Outside: behind the detonation point, past the end, or beyond the radius.
        assertFalse(cylinder.contains(10.0, 64.1, -3.0))
        assertFalse(cylinder.contains(10.0, 64.0 - cylinder.length - 0.1, -3.0))
        assertFalse(cylinder.contains(10.0 + cylinder.radius + 0.01, 60.0, -3.0))
        assertFalse(cylinder.contains(Double.NaN, 60.0, -3.0))
        // A box straddling the axis intersects; a box off to the side does not.
        assertTrue(cylinder.intersectsBox(9.0, 50.0, -4.0, 11.0, 52.0, -2.0))
        assertTrue(cylinder.intersectsBox(10.0 + cylinder.radius - 0.1, 50.0, -3.5, 20.0, 52.0, -2.5))
        assertFalse(cylinder.intersectsBox(10.0 + cylinder.radius + 0.1, 50.0, -3.5, 20.0, 52.0, -2.5))
        assertFalse(cylinder.intersectsBox(9.0, 65.0, -4.0, 11.0, 70.0, -2.0))
        val bounds = cylinder.bounds()
        assertEquals(10.0 - cylinder.radius, bounds[0], 1e-9)
        assertEquals(64.0 - cylinder.length, bounds[1], 1e-9)
        assertEquals(64.0, bounds[4], 1e-9)
        // A diagonal axis is normalized and its cylinder still holds the sphere's volume.
        val diagonal = BlastModel.penetratorCylinder(0.0, 0.0, 0.0, 3.0, -4.0, 0.0, 2.0, p)!!
        assertEquals(0.6, diagonal.dx, 1e-12); assertEquals(-0.8, diagonal.dy, 1e-12)
        assertEquals(BlastModel.sphereVolume(2.0), diagonal.volume, 1e-9)
        assertNull(BlastModel.penetratorCylinder(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, p))
        assertNull(BlastModel.penetratorCylinder(0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, p))
        assertTrue(BlastModel.queryRadius(BlastModel.radii(1000.0, p), p, cylinder) >= cylinder.length)
    }

    @Test fun `block force and visual tier scale with the charge`() {
        val rf = BlastModel.radii(100.0, p).fireball
        assertEquals(0.0, BlastModel.blockForce(100.0, rf, rf, 0.5, p), 1e-9)
        assertEquals(0.0, BlastModel.blockForce(100.0, rf + 0.1, rf, 0.5, p), 1e-9)
        assertEquals(8.0 * Math.cbrt(100.0), BlastModel.blockForce(100.0, 0.0, rf, 0.5, p), 1e-9)
        assertTrue(BlastModel.blockForce(1.0, 0.0, 0.5, 0.0, p) > 1.5, "a 1 kg charge breaks stone at its centre")
        assertEquals(8.0 * Math.cbrt(1000.0), BlastModel.cylinderBlockForce(1000.0, p), 1e-9)
        assertEquals(0, BlastModel.fireballTier(0.2))
        assertEquals(1, BlastModel.fireballTier(1.2))
        assertEquals(2, BlastModel.fireballTier(BlastModel.radii(100.0, p).fireball))
        assertEquals(3, BlastModel.fireballTier(BlastModel.radii(500.0, p).fireball))
        assertEquals(4, BlastModel.fireballTier(BlastModel.radii(1000.0, p).fireball))
        assertEquals(5, BlastModel.fireballTier(10.0))
        assertEquals(0, BlastModel.fireballTier(Double.NaN))
    }

    @Test fun `parameters reject unordered or invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { BlastParameters(fireballK = 2.0, severeK = 1.8) }
        assertThrows(IllegalArgumentException::class.java) { BlastParameters(exposureFloor = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { BlastParameters(vehicleDamagePerKg = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { BlastParameters(penetratorRadiusFactor = 0.0) }
    }
}
