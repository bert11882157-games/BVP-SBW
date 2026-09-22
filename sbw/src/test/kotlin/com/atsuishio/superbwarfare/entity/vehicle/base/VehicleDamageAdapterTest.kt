package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

/** Compiled wiring checks complement transaction execution; they do not simulate a game world. */
class VehicleDamageAdapterTest {
    private val base = "com/atsuishio/superbwarfare/entity/vehicle/base/"
    private data class Call(val owner: String, val name: String)
    private data class Method(
        val name: String,
        val descriptor: String,
        val access: Int,
        val calls: MutableList<Call> = mutableListOf(),
    )

    private fun methods(className: String): List<Method> {
        val result = mutableListOf<Method>()
        val stream = javaClass.classLoader.getResourceAsStream("$base$className.class")
            ?: error("Missing compiled damage class: $className")
        stream.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val method = Method(name.substringBefore('$'), descriptor, access)
                    result += method
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            method.calls += Call(owner, name.substringBefore('$'))
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        return result
    }

    @Test
    fun `public damage facade still delegates through the existing service signatures`() {
        val entity = methods("VehicleEntity")
        val expected = mapOf("acceptsDamageSource" to "acceptsSource", "hurt" to "hurt",
            "applyResolvedDamage" to "applyResolved", "computeVehicleDamageAfterModifiers" to "computeAfterModifiers")
        for ((facade, target) in expected) {
            val method = entity.single { it.name == facade }
            assertNotEquals(0, method.access and Opcodes.ACC_PUBLIC, facade)
            val domainCalls = method.calls.filterNot {
                it == Call("kotlin/jvm/internal/Intrinsics", "checkNotNullParameter")
            }
            assertEquals(listOf(Call("${base}VehicleDamageLifecycleService", target)), domainCalls)
        }
        assertEquals(0, entity.single { it.name == "getDamageModifier" }.access and Opcodes.ACC_FINAL)
        val vanilla = entity.single { it.name == "invokeVanillaHurt" }
        assertEquals(1, vanilla.calls.count { it.name == "hurt" && it.owner != "${base}VehicleEntity" })
    }

    @Test
    fun `both adapter entry points reach the same transaction and preserve request projection order`() {
        val service = methods("VehicleDamageLifecycleService")
        assertEquals(1, service.single { it.name == "hurt" }.calls.count {
            it == Call("${base}VehicleDamageTransaction", "hurt")
        })
        val resolved = service.single { it.name == "applyResolved" }.calls
        val getters = resolved.filter { it.owner.endsWith("/ResolvedVehicleDamageRequest") }.map { it.name }
        assertEquals(listOf("getSource", "getAmount", "getLethal", "getModulePolicy", "getFeedback", "getDestructionContext"), getters)
        assertEquals(1, resolved.count { it == Call("${base}VehicleDamageTransaction", "applyResolved") })
    }

    @Test
    fun `damage access forwards to facade hooks rather than bypassing their owners`() {
        val access = methods("VehicleDamageLifecycleService\$transaction\$1")
        val hooks = mapOf("acceptsSource" to "acceptsDamageSource", "reportDebug" to "reportDamageDebug",
            "computeAfterModifiers" to "computeVehicleDamageAfterModifiers", "invokeVanillaHurt" to "invokeVanillaHurt",
            "defaultDestructionContext" to "defaultDestructionContext", "destroy" to "destroy",
            "getHealth" to "getHealth", "getMaxHealth" to "getMaxHealth", "isWreck" to "isWreck", "isAlive" to "isAlive")
        for ((method, hook) in hooks) {
            assertTrue(access.filter { it.name == method }.any { candidate ->
                candidate.calls.any { it == Call("${base}VehicleEntity", hook) }
            }, "$method must delegate to VehicleEntity.$hook")
        }
    }

    @Test
    fun `native commit retains attribution module damage stamps and hull callback order`() {
        val commit = methods("VehicleDamageLifecycleService").single { it.name == "commit" }
        val names = commit.calls.map { it.name }
        val ordered = listOf("setCrash", "setLastAttackerUUID", "suppressesNativeVehicleModuleDamage",
            "getInstance", "setTurretHealth", "setLeftWheelHealth", "setRightWheelHealth",
            "setMainEngineHealth", "setSubEngineHealth", "setLastDamageSource", "setLastDamageStamp", "onHurt")
        var cursor = 0
        for (name in ordered) {
            val offset = names.drop(cursor).indexOf(name)
            assertTrue(offset >= 0, "Missing or reordered $name in $names")
            cursor += offset + 1
        }
    }
}
