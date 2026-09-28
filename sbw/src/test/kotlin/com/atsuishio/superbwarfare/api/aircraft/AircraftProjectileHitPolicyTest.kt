package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class AircraftProjectileHitPolicyTest {
    @Test
    fun `gun rounds follow the owner table and interpolate between its calibres`() {
        for ((caliber, damage) in listOf(7.62 to 0.8, 12.7 to 2.5, 14.5 to 3.5, 20.0 to 14.0, 23.0 to 17.0,
            25.0 to 20.0, 30.0 to 26.0, 37.0 to 36.0, 40.0 to 42.0, 57.0 to 80.0)) {
            assertEquals(damage, AircraftHitRules.gun(caliber)!!, 1e-9, "$caliber mm")
        }
        assertEquals(23.0, AircraftHitRules.gun(27.5)!!, 1e-9)
        assertTrue(AircraftHitRules.gun(5.56)!! < 0.8)
        assertEquals(150.0, AircraftHitRules.gun(70.0)!!, 1e-9)
    }

    @Test
    fun `unknown invalid and submillimetre rounds are explicitly unsupported`() {
        for (caliber in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            -10.0, -0.0, 0.0, 0.99)) {
            assertNull(AircraftProjectileHitPolicy.damageForCaliberMillimetres(caliber))
        }
    }

    @Test
    fun `fighters survive a lot of fifty calibre and a short cannon burst but not a long one`() {
        val f16 = 410.0
        assertTrue(f16 / AircraftHitRules.gun(12.7)!! > 150, ".50 hits to kill")
        val m61 = 2 * AircraftHitRules.gun(20.0)!!   // consolidated fast-firing round
        assertTrue(f16 / m61 in 10.0..20.0)
    }

    @Test
    fun `heavy weapons follow the aircraft rules`() {
        assertEquals(AircraftHitRules.Kind.TANK_SHELL, AircraftHitRules.kind("tank_shell", "APFSDS", 125.0))
        assertEquals(AircraftHitRules.Kind.TANK_SHELL, AircraftHitRules.kind("autocannon_shell", "HE", 76.0))
        assertEquals(AircraftHitRules.Kind.ROCKET, AircraftHitRules.kind("rocket", "ATGM", 110.0))
        assertEquals(AircraftHitRules.Kind.ATGM, AircraftHitRules.kind("atgm", "ATGM", 130.0))
        assertEquals(AircraftHitRules.Kind.MISSILE, AircraftHitRules.kind("atgm", "HE", 152.0))
        assertEquals(AircraftHitRules.Kind.MISSILE, AircraftHitRules.kind("sam", "HE", 70.0))
        assertEquals(AircraftHitRules.Kind.GUN, AircraftHitRules.kind("autocannon_shell", "HE", 30.0))
        // RPG: crippling, never killing a fighter; capped for big airframes
        assertEquals(287.0, AircraftHitRules.rocket(410.0), 1e-9)
        assertEquals(350.0, AircraftHitRules.rocket(2000.0), 1e-9)
        // ATGM: devastating except to large aircraft
        assertEquals(369.0, AircraftHitRules.atgm(410.0), 1e-9)
        assertEquals(600.0, AircraftHitRules.atgm(2000.0), 1e-9)
    }

    @Test
    fun `small missiles cripple fighters and large ones destroy them`() {
        val f16 = 410.0; val su27 = 740.0
        val sidewinder = AircraftHitRules.missile(4.6, f16)
        assertTrue(sidewinder in 0.55 * f16..0.75 * f16)
        assertTrue(AircraftHitRules.missile(0.53, f16) >= 0.55 * f16 - 1e-9)   // MANPADS
        assertTrue(AircraftHitRules.missile(4.6, 2800.0) <= 0.75 * 600.0)      // bombers count as 600 HP
        assertTrue(AircraftHitRules.missile(9.2, su27) > su27)                 // AIM-120
        assertTrue(AircraftHitRules.missile(24.0, su27) > su27)                // R-27
        assertEquals(0.6 * AircraftHitRules.missile(24.0, su27), AircraftHitRules.missile(24.0, su27,
            AircraftHitRules.proximity(5.0, 5.0)), 1e-9)
        assertEquals(0.0, AircraftHitRules.proximity(5.01, 5.0))
    }

    @Test
    fun `aircraft types include helicopters and never depend on lightly armored trait`() {
        val aircraft = setOf(VehicleType.AIRPLANE, VehicleType.HELICOPTER, VehicleType.DRONE)
        for (type in VehicleType.entries) assertEquals(type in aircraft, AircraftProjectileHitPolicy.appliesTo(type))
        assertFalse(AircraftProjectileHitPolicy.appliesTo(null))
    }

    @Test
    fun `receipt survives save and isolates projectile and target identity`() {
        val firstProjectile = CompoundTag()
        val secondProjectile = CompoundTag()
        val target = UUID(1, 2)
        val secondTarget = UUID(1, 3)
        assertTrue(AircraftProjectileHitReceipts.claim(firstProjectile, target))
        repeat(20) { assertFalse(AircraftProjectileHitReceipts.claim(firstProjectile, target)) }
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { NbtIo.write(firstProjectile, it) }
        }.toByteArray()
        val restored = DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it) }!!
        assertFalse(AircraftProjectileHitReceipts.claim(restored, target))
        assertTrue(AircraftProjectileHitReceipts.claim(restored, secondTarget))
        assertTrue(AircraftProjectileHitReceipts.claim(secondProjectile, target))
        assertFalse(AircraftProjectileHitReceipts.contains(firstProjectile, secondTarget))
    }

    @Test
    fun `receipt storage is bounded and malformed saved state cannot reopen duplicate damage`() {
        val data = CompoundTag()
        repeat(AircraftProjectileHitReceipts.MAX_TARGETS) {
            assertTrue(AircraftProjectileHitReceipts.claim(data, UUID(0, it.toLong())))
        }
        repeat(100) { assertFalse(AircraftProjectileHitReceipts.claim(data, UUID(1, it.toLong()))) }
        assertEquals(AircraftProjectileHitReceipts.MAX_TARGETS,
            data.getCompound(AircraftProjectileHitReceipts.KEY).size())
        val malformed = CompoundTag().apply { putString(AircraftProjectileHitReceipts.KEY, "bad") }
        assertFalse(AircraftProjectileHitReceipts.claim(malformed, UUID(9, 9)))
    }

    @Test
    fun `one missile physical hit plus native direct and explosion followups charges HP once`() {
        val data = CompoundTag()
        val struck = UUID(2, 1)
        val nearby = UUID(2, 2)
        var health = 250f
        assertTrue(AircraftProjectileHitReceipts.claim(data, struck))
        health -= 100f
        for (explosion in listOf(false, true, true)) {
            assertEquals(AircraftProjectileDamageRoute.DUPLICATE,
                AircraftProjectileHitPolicy.nativeRoute(
                    AircraftProjectileHitReceipts.contains(data, struck), explosion, true))
        }
        assertEquals(150f, health)
        assertEquals(AircraftProjectileDamageRoute.UNRELATED,
            AircraftProjectileHitPolicy.nativeRoute(
                AircraftProjectileHitReceipts.contains(data, nearby), true, true))
        assertEquals(AircraftProjectileDamageRoute.PHYSICAL_HIT,
            AircraftProjectileHitPolicy.nativeRoute(false, false, true))
        assertEquals(AircraftProjectileDamageRoute.UNRELATED,
            AircraftProjectileHitPolicy.nativeRoute(false, false, false))
    }
}
