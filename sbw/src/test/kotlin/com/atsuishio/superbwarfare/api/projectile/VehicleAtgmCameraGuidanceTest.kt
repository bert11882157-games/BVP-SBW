package com.atsuishio.superbwarfare.api.projectile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

/** Checks the production call sites, not a copied guidance implementation. */
class VehicleAtgmCameraGuidanceTest {
    private fun calls(owner: String, method: String): Set<String> {
        val result = linkedSetOf<String>()
        var found = false
        javaClass.classLoader.getResourceAsStream("com/atsuishio/superbwarfare/$owner.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String,
                                         signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name != method) return null
                    found = true
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                     descriptor: String, isInterface: Boolean) {
                            result += "$owner.$name"
                        }
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                            result += "$owner.$name"
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue(found, "missing production method $owner.$method")
        return result
    }

    @Test
    fun `ground launches and helicopter launches share authenticated camera authority`() {
        val entity = "entity/vehicle/base/VehicleEntity"
        val launch = calls(entity, "resolveLaunchedWeaponGuidanceRay")
        assertTrue(launch.any { it.endsWith(".resolveHelicopterAtgmGuidanceRay") })
        assertFalse(launch.any { it.contains("Muzzle") || it.contains("Barrel") })
        val accepted = calls(entity, "resolveHelicopterAtgmGuidanceRay")
        assertTrue(accepted.any { it.endsWith(".isFresh") })
        assertFalse(accepted.any { it.endsWith(".getVehicleType") || it.endsWith(".getHelicopterAtgm") })
    }

    @Test
    fun `camera transport admits ground seats and publishes the composed screen ray`() {
        val client = "client/VehicleHelicopterAtgmCameraRayClient"
        val transport = "network/VehicleHelicopterAtgmCameraRayTransport"
        for (methods in listOf(calls(client, "contextOf"), calls(transport, "admit"))) {
            assertFalse(methods.any { it.endsWith(".getVehicleType") || it.endsWith(".HELICOPTER") })
        }
        val sample = calls(client, "capturePresented")
        assertTrue(sample.any { it.endsWith(".getLookVector") })
        assertTrue(sample.any { it.endsWith(".getPosition") })
        assertFalse(sample.any { it.contains("Barrel") || it.contains("Muzzle") })
    }

    @Test
    fun `missing camera sample cannot silently fall back to barrel steering`() {
        val missile = calls("entity/projectile/WireGuideMissileEntity", "tick")
        assertTrue(missile.any { it.endsWith(".resolveLaunchedWeaponGuidanceRay") })
        assertFalse(missile.any { it.endsWith(".getBarrelVector") || it.endsWith(".getShootPosForHud") })
    }
}
