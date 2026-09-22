package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.weapon.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

class VehicleShotTransactionTest {
    @Test fun `module rejection precedes muzzle ammo projectile and presentation for both slots`() {
        for (weapon in listOf(null, "primary", "secondary", "targeted-hmg")) {
            var moduleChecks = 0
            val result = VehicleShotTransaction.execute<String>(weapon, false, true,
                allowsFire = { moduleChecks++; false },
                resolve = { fail("muzzle must not be resolved") },
                shoot = { fail("ammo and projectile factory must not run") },
                accepted = { fail("recoil and audio must not run") })
            assertEquals(ShotRejectionReason.ACTION_BLOCKED, result.reason)
            assertEquals(1, moduleChecks)
        }
    }

    @Test fun `wreck and client authority cannot reach mutable gameplay`() {
        for ((wreck, server, reason) in listOf(
            Triple(true, true, ShotRejectionReason.WRECKED),
            Triple(false, false, ShotRejectionReason.NOT_SERVER_AUTHORITY))) {
            val result = VehicleShotTransaction.execute<String>("cannon", wreck, server,
                { fail("no action callback") }, { fail("no lookup") },
                { fail("no shot") }, { fail("no effects") })
            assertEquals(reason, result.reason)
        }
    }

    @Test fun `restored modules allow exactly one accepted transaction with pinned context`() {
        var selected = "cannon"
        val order = mutableListOf<String>()
        val result = VehicleShotTransaction.execute(null, false, true,
            allowsFire = { order += "admit"; true },
            resolve = { order += "resolve"; selected },
            shoot = { context ->
                order += "fire:$context"
                selected = "coax"
                ShotResult(ShotStatus.ACCEPTED, ShotRejectionReason.NONE, context, null, null, null, emptyList())
            },
            accepted = { order += "effects:$it" })
        assertTrue(result.isAccepted())
        assertEquals(listOf("admit", "resolve", "fire:cannon", "effects:cannon"), order)
    }

    @Test fun `failed projectile creation cannot emit accepted effects`() {
        val result = VehicleShotTransaction.execute("cannon", false, true, { true }, { "cannon" },
            { ShotResult.rejected(ShotRejectionReason.PROJECTILE_CREATION_FAILED, it) },
            { fail("no recoil audio or accepted-shot effects") })
        assertFalse(result.isAccepted())
    }

    @Test fun `all compiled base overloads use one runtime and addon baseTick remains overridable`() {
        val owner = "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity"
        val routes = linkedMapOf<String, Int>()
        var baseTickAccess: Int? = null
        javaClass.classLoader.getResourceAsStream("$owner.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name == "baseTick" && descriptor == "()V") baseTickAccess = access
                    if (name != "vehicleShootResult") return null
                    assertEquals(0, access and Opcodes.ACC_FINAL, "public addon adapter became final")
                    routes[descriptor] = 0
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, calledOwner: String, name: String, desc: String, isInterface: Boolean) {
                            if (calledOwner.endsWith("/VehicleWeaponRuntime") && name == "fire") {
                                routes[descriptor] = routes.getValue(descriptor) + 1
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertEquals(3, routes.size)
        assertTrue(routes.values.all { it == 1 }, routes.toString())
        assertNotNull(baseTickAccess)
        assertEquals(0, baseTickAccess!! and Opcodes.ACC_FINAL)
    }

    @Test fun `compiled tick order retains one vanilla tick then movement pose aim fire recoil`() {
        fun calls(className: String): Map<String, List<String>> {
            val methods = linkedMapOf<String, MutableList<String>>()
            javaClass.classLoader.getResourceAsStream("com/atsuishio/superbwarfare/entity/vehicle/base/$className.class")!!.use {
                ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                        val output = methods.getOrPut(name.substringBefore('$')) { mutableListOf() }
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                                output += "${owner.substringAfterLast('/')}.${name.substringBefore('$')}"
                            }
                        }
                    }
                }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            return methods
        }
        val pipeline = calls("VehicleTickPipeline").getValue("afterVanillaLifecycle")
            .filter { it.startsWith("VehicleEntity.tickPipeline") }.map { it.substringAfter('.') }
        assertEquals(listOf("tickPipelineLifecycleAfterVanilla", "tickPipelineTravel",
            "tickPipelineControlBeforeMovement", "tickPipelineMovement", "tickPipelinePostMovement",
            "tickPipelinePoseDamageAndChunk", "tickPipelineAimAndWeapons", "tickPipelineRecoil"), pipeline)
        val entity = calls("VehicleEntity")
        val lifecycle = entity.getValue("baseTick").filter {
            it.endsWith(".baseTick") || it.startsWith("VehicleTickPipeline.")
        }
        assertEquals(3, lifecycle.size)
        assertEquals("VehicleTickPipeline.beforeVanillaLifecycle", lifecycle.first())
        assertTrue(lifecycle[1].endsWith(".baseTick"))
        assertEquals("VehicleTickPipeline.afterVanillaLifecycle", lifecycle.last())
        val aimFire = entity.getValue("tickPipelineAimAndWeapons")
        val aim = aimFire.indexOf("VehicleAimController.tickServer")
        val fire = aimFire.indexOf("VehicleWeaponScheduler.tick")
        assertTrue(aim >= 0 && fire > aim)
        assertEquals(1, entity.getValue("tickPipelinePoseDamageAndChunk").count { it == "VehicleEntity.updateVehiclePoseLifecycle" })
    }
}
