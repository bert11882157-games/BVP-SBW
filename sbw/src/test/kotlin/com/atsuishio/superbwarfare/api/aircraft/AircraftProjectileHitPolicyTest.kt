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
    fun `all lower inclusive and upper exclusive band boundaries are exact`() {
        val boundaries = listOf(1.0 to 1f, 12.7 to 2.5f, 20.0 to 6f,
            30.0 to 9f, 50.0 to 30f, 80.0 to 100f)
        var below: Float? = null
        for ((caliber, expected) in boundaries) {
            assertEquals(below, AircraftProjectileHitPolicy.damageForCaliberMillimetres(Math.nextDown(caliber)))
            assertEquals(expected, AircraftProjectileHitPolicy.damageForCaliberMillimetres(caliber))
            assertEquals(expected, AircraftProjectileHitPolicy.damageForCaliberMillimetres(Math.nextUp(caliber)))
            below = expected
        }
        assertEquals(100f, AircraftProjectileHitPolicy.damageForCaliberMillimetres(Double.MAX_VALUE))
    }

    @Test
    fun `representative bullets shells and missile bodies use only physical diameter`() {
        for ((caliber, damage) in listOf(7.62 to 1f, 12.7 to 2.5f, 14.5 to 2.5f,
            23.0 to 6f, 30.0 to 9f, 57.0 to 30f, 73.0 to 30f, 80.0 to 100f,
            125.0 to 100f, 152.0 to 100f)) {
            assertEquals(damage, AircraftProjectileHitPolicy.damageForCaliberMillimetres(caliber))
        }
    }

    @Test
    fun `unknown invalid and submillimetre rounds are explicitly unsupported`() {
        for (caliber in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            -10.0, -0.0, 0.0, 0.99)) {
            assertNull(AircraftProjectileHitPolicy.damageForCaliberMillimetres(caliber))
        }
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
        health -= AircraftProjectileHitPolicy.damageForCaliberMillimetres(152.0)!!
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
