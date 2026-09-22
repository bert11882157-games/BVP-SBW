package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

class VehicleLandingGearFireAdmissionTest {
    @Test
    fun `default is disabled and explicit serialized opt in round trips`() {
        assertFalse(DefaultGunData().requiresRetractedLandingGear)
        val data = Json.decodeFromString(DefaultGunData.serializer(),
            """{"RequiresRetractedLandingGear":true}""")
        assertTrue(data.requiresRetractedLandingGear)
        val encoded = Json.encodeToString(DefaultGunData.serializer(), data)
        assertTrue(Json.decodeFromString(DefaultGunData.serializer(), encoded)
            .requiresRetractedLandingGear)
    }

    @Test
    fun `opt in accepts only commanded fully retracted finite gear`() {
        val fractions = listOf(-1F, 0F, 0.05F, 0.5F, 0.95F, Math.nextDown(1F),
            1F, Math.nextUp(1F), Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (fraction in fractions) {
            assertFalse(permitsLandingGearShot(true, false, fraction))
            assertEquals(fraction == 1F, permitsLandingGearShot(true, true, fraction))
            for (up in listOf(false, true)) {
                assertTrue(permitsLandingGearShot(false, up, fraction))
            }
        }
    }

    @Test
    fun `compiled common transaction checks gear before belt and mutable shot`() {
        val calls = mutableListOf<String>()
        val owner = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleWeaponRuntime"
        javaClass.classLoader.getResourceAsStream("$owner.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int, name: String, descriptor: String, signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != "performShot") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int, owner: String, name: String, descriptor: String,
                            isInterface: Boolean,
                        ) {
                            calls += "$owner.$name"
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        val gate = calls.indexOfFirst { it.endsWith("VehicleWeaponRuntimeKt.permitsLandingGearShot") }
        val belt = calls.indexOfFirst { it.endsWith("GunData.resolveProjectileBelt") }
        val shot = calls.indexOfFirst { it.endsWith("GunData.shootWithResult") }
        assertTrue(gate >= 0 && belt > gate && shot > belt, calls.toString())
        assertEquals(1, calls.count { it.endsWith("VehicleWeaponRuntimeKt.permitsLandingGearShot") })
    }
}
