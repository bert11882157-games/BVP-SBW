package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * A recovered vehicle item must place again when its selected weapon is a suspended-armament store (the owner's
 * report, 2026-09-28: "when you add any suspended armament and pick up the vehicle, you can't place it back down").
 * Checked on the compiled production class: the index check uses the full weapon list and the fitted armament
 * travels with the item.
 */
class VehicleItemStateWiringTest {
    private fun calls(method: String): List<String> {
        val found = mutableListOf<String>()
        javaClass.classLoader.getResourceAsStream("com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                                         exceptions: Array<out String>?): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, called: String, d: String, i: Boolean) {
                            found += called
                        }
                        override fun visitLdcInsn(value: Any?) { if (value is String) found += "ldc:$value" }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue(found.isNotEmpty(), "missing VehicleEntity.$method")
        return found
    }

    @Test fun `weapon slot indices are checked against native weapons plus store channels`() {
        val check = calls("validVehicleItemWeaponIndices")
        assertTrue("getWeaponIds" in check, "must count the store channels the slots can select")
        assertFalse("weapons" in check, "the native-only seat list refuses a selected store")
    }

    @Test fun `the fitted armament is written into and read back from the item state`() {
        assertTrue(calls("createVehicleItemState").contains("armamentForVehicleItem"))
        assertTrue(calls("armamentForVehicleItem").contains("ldc:LastFire"), "launch timers are per world")
        val restore = calls("restoreVehicleItemState")
        assertTrue(restore.contains("ldc:AircraftArmament") && restore.contains("getPersistentData"))
    }
}
