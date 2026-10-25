package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/** Compiled production wiring checks; no game-world simulation or renderer is substituted. */
class AircraftProjectileDamageIntegrationTest {
    private val root = "com/atsuishio/superbwarfare/"
    private data class Call(val owner: String, val name: String)
    private data class Method(
        val calls: MutableList<Call> = mutableListOf(),
        val fields: MutableList<String> = mutableListOf(),
        val events: MutableList<String> = mutableListOf(),
    )

    private val commitDescriptor = "(L${root}entity/vehicle/base/VehicleEntity;" +
        "Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/world/damagesource/DamageSource;" +
        "L${root}api/projectile/impact/ProjectileImpactContext;)Z"
    private val taczDescriptor = "(Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/phys/Vec3;" +
        "Lnet/minecraft/world/phys/Vec3;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"

    private fun node(path: String, name: String, descriptor: String? = null, synthetic: Boolean = false): MethodNode {
        val type = ClassNode()
        val bytes = javaClass.classLoader.getResourceAsStream("$root$path.class")
            ?: error("Missing compiled production class: $path")
        bytes.use { ClassReader(it).accept(type, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        return type.methods.single {
            it.name == name && (it.access and Opcodes.ACC_SYNTHETIC != 0) == synthetic &&
                (descriptor == null || it.desc == descriptor)
        }
    }

    private fun method(path: String, name: String, descriptor: String? = null): Method =
        summarize(node(path, name, descriptor))

    private fun summarize(node: MethodNode): Method {
        val result = Method()
        for (instruction in node.instructions) when (instruction) {
            is MethodInsnNode -> {
                result.calls += Call(instruction.owner, instruction.name.substringBefore('$'))
                result.events += instruction.name.substringBefore('$')
            }
            is FieldInsnNode -> {
                result.fields += "${instruction.owner}.${instruction.name}"
                result.events += "field:${instruction.name}"
            }
            else -> if (instruction.opcode in listOf(Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.RETURN)) {
                result.events += "return"
            }
        }
        return result
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
        val core = node("api/aircraft/AircraftProjectileDamage", "applyPhysicalHit", commitDescriptor)
        assertPhysicalCommit(core)
        val commit = summarize(core)
        val claim = commit.events.indexOf("claim")
        val caliber = commit.events.indexOf("resolveMillimetres")
        val override = commit.events.indexOf("aircraftDirectHitDamage")
        val damage = commit.events.indexOf("kindOf")
        val applied = commit.events.indexOf("applyResolvedDamage")
        assertTrue(claim >= 0 && caliber > claim && damage > caliber && applied > damage)
        assertTrue(override in (claim + 1) until caliber)
        assertTrue(commit.events.indexOf("getRootVehicle") in (claim + 1) until caliber)
        assertEquals(1, commit.events.count { it == "applyResolvedDamage" })
        for (rule in listOf("gun", "rocket", "atgm", "missile")) {
            val at = commit.calls.indexOf(Call("${root}api/aircraft/AircraftHitRules", rule))
            assertTrue(at >= 0 && at < commit.calls.indexOf(Call("${root}entity/vehicle/base/VehicleEntity", "applyResolvedDamage")), rule)
        }
        assertTrue(commit.fields.any { it.endsWith("AircraftHitRules${'$'}Kind.TANK_SHELL") })
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
        val compiled = node("mixins/tacz/EntityKineticBulletMixin", "sbw\$entityImpact", taczDescriptor)
        assertTaczRouting(compiled)
        val hit = summarize(compiled)
        assertTrue(hit.calls.any { it.name == "continuesDefaultPipeline" })
        assertTrue(hit.calls.any { it.name == "consumesProjectile" })
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
    /** Normal-return paths only. Unknown conditions fork; known adapter facts constrain only their own branches. */
    private fun returnedCalls(
        method: MethodNode,
        watched: List<Call>,
        branch: (AbstractInsnNode?, Int) -> Boolean?,
    ): Set<List<Int>> {
        val code = method.instructions.toArray()
        data class State(val pc: Int, val counts: List<Int>)
        val pending = ArrayDeque<State>()
        val seen = HashSet<State>()
        val returned = HashSet<List<Int>>()
        pending.add(State(0, List(watched.size) { 0 }))
        fun add(pc: Int, counts: List<Int>) { if (pc in code.indices) pending.add(State(pc, counts)) }
        while (pending.isNotEmpty()) {
            val state = pending.removeFirst()
            if (!seen.add(state)) continue
            val instruction = code[state.pc]
            val counts = state.counts.toMutableList()
            if (instruction is MethodInsnNode) {
                val index = watched.indexOf(Call(instruction.owner, instruction.name))
                if (index >= 0) {
                    counts[index]++
                    assertTrue(counts[index] <= 2, "unexpected repeated commit/adapter call or loop")
                }
            }
            when (instruction) {
                is JumpInsnNode -> {
                    var previous = instruction.previous
                    while (previous != null && previous.opcode < 0) previous = previous.previous
                    val decision = if (instruction.opcode == Opcodes.GOTO) true else branch(previous, instruction.opcode)
                    if (decision != false) add(method.instructions.indexOf(instruction.label), counts)
                    if (decision != true) add(state.pc + 1, counts)
                }
                is TableSwitchInsnNode -> (instruction.labels + instruction.dflt).forEach {
                    add(method.instructions.indexOf(it), counts)
                }
                is LookupSwitchInsnNode -> (instruction.labels + instruction.dflt).forEach {
                    add(method.instructions.indexOf(it), counts)
                }
                else -> when (instruction.opcode) {
                    Opcodes.RETURN, Opcodes.IRETURN, Opcodes.ARETURN -> returned.add(counts)
                    Opcodes.ATHROW -> Unit
                    else -> add(state.pc + 1, counts)
                }
            }
        }
        assertTrue(returned.isNotEmpty(), "no normal return paths inspected")
        return returned
    }

    private fun booleanJump(opcode: Int, value: Boolean): Boolean? = when (opcode) {
        Opcodes.IFEQ -> !value
        Opcodes.IFNE -> value
        else -> null
    }

    private fun assertTaczRouting(compiled: MethodNode) {
        val watched = listOf(
            Call("${root}api/aircraft/AircraftProjectileDamage", "resolve"),
            Call("${root}mixins/tacz/EntityKineticBulletMixin", "sbw\$apply"),
            Call("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel"),
        )
        for (aircraft in listOf(false, true)) for (profileNull in listOf(false, true)) {
            for (removed in listOf(false, true)) {
                val outcomes = returnedCalls(compiled, watched) { previous, opcode ->
                    when {
                        previous is FieldInsnNode && previous.name == "sbw\$profileId" -> when (opcode) {
                            Opcodes.IFNULL -> profileNull
                            Opcodes.IFNONNULL -> !profileNull
                            else -> null // Logging/feedback field reads are not gates by themselves.
                        }
                        previous is FieldInsnNode && previous.name == "isClientSide" -> booleanJump(opcode, false)
                        previous is MethodInsnNode && previous.owner == "${root}api/aircraft/AircraftProjectileDamage" &&
                            previous.name == "isAircraft" -> booleanJump(opcode, aircraft)
                        previous is MethodInsnNode && previous.name == "isRemoved" -> booleanJump(opcode, removed)
                        else -> null
                    }
                }
                for (calls in outcomes) {
                    val context = "aircraft=$aircraft profileNull=$profileNull removed=$removed calls=$calls"
                    assertEquals(if (aircraft && !removed) 1 else 0, calls[0], context)
                    assertEquals(if (!aircraft && !profileNull && !removed) 1 else 0, calls[1], context)
                    if (removed && (aircraft || !profileNull)) assertEquals(1, calls[2], context)
                }
            }
        }
    }

    private fun assertPhysicalCommit(core: MethodNode) {
        val claim = Call("${root}api/aircraft/AircraftProjectileHitReceipts", "claim")
        val commit = Call("${root}entity/vehicle/base/VehicleEntity", "applyResolvedDamage")
        val codeCalls = core.instructions.filterIsInstance<MethodInsnNode>()
        assertEquals(1, codeCalls.count { it.owner == claim.owner && it.name == claim.name })
        assertEquals(1, codeCalls.count { it.owner == commit.owner && it.name == commit.name &&
            it.desc == "(L${root}api/vehicle/damage/ResolvedVehicleDamageRequest;)L${root}api/vehicle/damage/ResolvedVehicleDamageResult;" })
        for (accepted in listOf(false, true)) {
            val outcomes = returnedCalls(core, listOf(claim, commit)) { previous, opcode ->
                if (previous is MethodInsnNode && previous.owner == claim.owner && previous.name == claim.name)
                    booleanJump(opcode, accepted) else null
            }
            for (calls in outcomes) {
                assertEquals(1, calls[0], "all normally returned physical contacts claim first")
                assertTrue(calls[1] in 0..1)
                if (!accepted) assertEquals(0, calls[1], "duplicate receipt must return without HP commit")
            }
            if (accepted) assertTrue(outcomes.any { it[1] == 1 }, "accepted supported damage can reach HP commit")
        }
    }

    @Test fun `default argument bridge delegates once without its own receipt or HP commit`() {
        val bridgeDescriptor = "(L${root}api/aircraft/AircraftProjectileDamage;" +
            commitDescriptor.removePrefix("(").removeSuffix(")Z") + "ILjava/lang/Object;)Z"
        val bridge = node("api/aircraft/AircraftProjectileDamage", "applyPhysicalHit\$default", bridgeDescriptor, true)
        val calls = bridge.instructions.filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.count { it.owner == "${root}api/aircraft/AircraftProjectileDamage" &&
            it.name == "applyPhysicalHit" && it.desc == commitDescriptor })
        assertFalse(calls.any { it.name in setOf("claim", "applyResolvedDamage", "hurt", "explode") })
    }

    @Test fun `routing regression catches a real null-profile gate before the aircraft branch`() {
        val changed = node("mixins/tacz/EntityKineticBulletMixin", "sbw\$entityImpact", taczDescriptor)
        val proceed = LabelNode()
        val prematureGate = InsnList().apply {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(FieldInsnNode(Opcodes.GETFIELD, "${root}mixins/tacz/EntityKineticBulletMixin", "sbw\$profileId",
                "Lnet/minecraft/resources/ResourceLocation;"))
            add(JumpInsnNode(Opcodes.IFNONNULL, proceed))
            add(InsnNode(Opcodes.RETURN))
            add(proceed)
        }
        changed.instructions.insert(prematureGate)
        assertThrows(AssertionError::class.java) { assertTaczRouting(changed) }
    }

    @Test fun `physical regression catches a duplicate resolved HP commit`() {
        val changed = node("api/aircraft/AircraftProjectileDamage", "applyPhysicalHit", commitDescriptor)
        val commit = changed.instructions.filterIsInstance<MethodInsnNode>().single {
            it.owner == "${root}entity/vehicle/base/VehicleEntity" && it.name == "applyResolvedDamage"
        }
        // Preserve receiver/request operands and the final result shape while duplicating the side effect.
        changed.instructions.insertBefore(commit, InsnNode(Opcodes.DUP2))
        changed.instructions.insert(commit, InsnList().apply {
            add(InsnNode(Opcodes.POP))
            add(MethodInsnNode(commit.opcode, commit.owner, commit.name, commit.desc, commit.itf))
        })
        assertThrows(AssertionError::class.java) { assertPhysicalCommit(changed) }
    }

}
