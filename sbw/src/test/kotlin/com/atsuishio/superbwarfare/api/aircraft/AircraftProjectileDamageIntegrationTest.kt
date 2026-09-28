package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Compiled production wiring checks; no game-world simulation or renderer is substituted. */
class AircraftProjectileDamageIntegrationTest {
    private val root = "com/atsuishio/superbwarfare/"
    private data class Call(val owner: String, val name: String)
    private data class Method(
        val calls: MutableList<Call> = mutableListOf(),
        val fields: MutableList<String> = mutableListOf(),
        val events: MutableList<String> = mutableListOf(),
    )

    private fun method(path: String, name: String): Method {
        val found = mutableListOf<Method>()
        val bytes = javaClass.classLoader.getResourceAsStream("$root$path.class")
            ?: error("Missing compiled production class: $path")
        bytes.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, candidate: String, descriptor: String,
                    signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (candidate != name && candidate.substringBefore('$') != name) return null
                    val result = Method().also(found::add)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, called: String,
                            descriptor: String, isInterface: Boolean) {
                            result.calls += Call(owner, called.substringBefore('$'))
                            result.events += called.substringBefore('$')
                        }
                        override fun visitFieldInsn(opcode: Int, owner: String, field: String, descriptor: String) {
                            result.fields += "$owner.$field"
                            result.events += "field:$field"
                        }
                        override fun visitInsn(opcode: Int) {
                            if (opcode in listOf(Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.RETURN)) {
                                result.events += "return"
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        return found.single()
    }

    @Test
    fun `aircraft resolution returns before armor volumes handlers and pack presentation`() {
        val resolver = method("api/projectile/impact/ProjectileImpactResolver", "resolve")
        val aircraft = resolver.calls.indexOf(Call("${root}api/aircraft/AircraftProjectileDamage", "resolve"))
        val volumes = resolver.calls.indexOf(Call("${root}api/projectile/impact/VehicleImpactVolumes", "attach"))
        assertTrue(aircraft >= 0 && volumes > aircraft)
        val resolveEvent = resolver.events.indexOf("resolve")
        val attachEvent = resolver.events.indexOf("attach")
        assertTrue("return" in resolver.events.subList(resolveEvent + 1, attachEvent))
    }

    @Test
    fun `legacy hurt is intercepted before modifiers and still retains the ground transaction`() {
        val hurt = method("entity/vehicle/base/VehicleDamageLifecycleService", "hurt")
        val policy = hurt.calls.indexOf(Call("${root}api/aircraft/AircraftProjectileDamage", "handleNativeDamage"))
        val legacy = hurt.calls.indexOf(Call("${root}entity/vehicle/base/VehicleDamageTransaction", "hurt"))
        assertTrue(policy >= 0 && legacy > policy)
        assertEquals(1, hurt.calls.count { it.owner.endsWith("/VehicleDamageTransaction") })
    }

    @Test
    fun `physical commit claims first then resolves caliber and calls resolved HP once without armor`() {
        val commit = method("api/aircraft/AircraftProjectileDamage", "applyPhysicalHit")
        val claim = commit.events.indexOf("claim")
        val caliber = commit.events.indexOf("resolveMillimetres")
        val override = commit.events.indexOf("aircraftDirectHitDamage")
        val damage = commit.events.indexOf("resolveDamage")
        val applied = commit.events.indexOf("applyResolvedDamage")
        assertTrue(claim >= 0 && caliber > claim && damage > caliber && applied > damage)
        assertTrue(override in (claim + 1) until caliber)
        assertTrue(commit.events.indexOf("getRootVehicle") in (claim + 1) until caliber)
        assertEquals(1, commit.events.count { it == "applyResolvedDamage" })
        assertTrue(commit.fields.any { it.endsWith("ResolvedVehicleModulePolicy.SKIP_NATIVE") })
        assertFalse(commit.calls.any { it.name in setOf("hurt", "computeAfterModifiers", "explode") })
        assertFalse(commit.calls.any { "berts_vehicle_pack" in it.owner || "Armor" in it.owner })
    }

    @Test
    fun `SBW bullet and fast projectile families keep their common pre-native impact resolver`() {
        for ((type, methodName) in listOf("ProjectileEntity" to "onHit", "FastThrowableProjectile" to "onHit")) {
            val hit = method("entity/projectile/$type", methodName)
            val resolve = hit.calls.indexOf(Call("${root}api/projectile/impact/ProjectileImpactResolver", "resolve"))
            val native = hit.calls.indexOfFirst { it.name == "continuesDefaultPipeline" }
            assertTrue(resolve >= 0 && native > resolve, type)
        }
    }

    @Test
    fun `aircraft native result keeps disposal and TNT, drops the legacy burst, never zeroes projectile damage`() {
        // owner 2026-09-28: no SBW white burst on an aircraft hit; a TNT charge still detonates (fireball only)
        val init = method("api/aircraft/AircraftProjectileDamage", "<clinit>")
        assertTrue(init.calls.any { it.name == "suppressNativeModuleDamage" })
        assertTrue(init.calls.any { it.name == "suppressDefaultExplosion" })
        assertTrue(init.calls.any { it.name == "visualPolicy" })
        assertFalse(init.calls.any { it.name in setOf("residualDamage", "residualExplosion") })
        assertTrue(init.fields.any { it.endsWith("ProjectileImpactDisposition.DEFAULT") })
    }

    @Test
    fun `TacZ aircraft branch precedes optional profile gate and leaves native effects in charge`() {
        val hit = method("mixins/tacz/EntityKineticBulletMixin", "sbw\$entityImpact")
        val aircraft = hit.events.indexOf("isAircraft")
        val resolve = hit.calls.indexOf(Call("${root}api/aircraft/AircraftProjectileDamage", "resolve"))
        val profileGate = hit.events.indexOf("field:sbw\$profileId")
        assertTrue(aircraft >= 0 && resolve >= 0 && profileGate > aircraft)
        val resolvedEvent = hit.events.indexOf("resolve")
        assertTrue(resolvedEvent in (aircraft + 1) until profileGate)
        assertTrue("return" in hit.events.subList(resolvedEvent + 1, profileGate))
        assertTrue("continuesDefaultPipeline" in hit.events.subList(resolvedEvent + 1, profileGate))
        assertTrue("consumesProjectile" in hit.events.subList(resolvedEvent + 1, profileGate))
        assertFalse(hit.calls.any { it.name in setOf("explode", "getDamage", "hurt") })
        val block = method("mixins/tacz/EntityKineticBulletMixin", "sbw\$blockImpact")
        assertFalse(block.calls.any { "AircraftProjectileDamage" in it.owner })
        assertTrue(block.fields.any { it.endsWith(".sbw\$profileId") })
    }

    @Test
    fun `TacZ damage uses only constructor captured ammo getter never current shooter or profile`() {
        val damage = method("mixins/tacz/EntityKineticBulletMixin", "aircraftDirectHitDamage")
        assertEquals(listOf("getAmmoId", "damageForAmmo"), damage.events.filterNot { it == "return" })
        assertTrue(damage.calls.any { it.owner == "${root}compat/tacz/TaczAircraftDamage" })
        assertFalse(damage.calls.any { it.name in setOf("getGunId", "getOwner", "getMainHandItem", "getDamage") })
        assertTrue(damage.fields.isEmpty())
    }
}
