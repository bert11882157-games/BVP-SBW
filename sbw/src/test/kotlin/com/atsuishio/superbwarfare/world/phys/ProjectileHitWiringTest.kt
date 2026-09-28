package com.atsuishio.superbwarfare.world.phys

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Label

/** Checks the compiled production entry points, not an alternate projectile or world model. */
class ProjectileHitWiringTest {
    private val root = "com/atsuishio/superbwarfare/"
    private data class Call(val owner: String, val name: String)
    private data class Method(val calls: MutableList<Call> = mutableListOf(),
                              val fields: MutableList<String> = mutableListOf(), var objectReturns: Int = 0)

    private fun methods(path: String, name: String): List<Method> {
        val found = mutableListOf<Method>()
        val bytes = javaClass.classLoader.getResourceAsStream("$root$path.class")
            ?: error("Missing compiled production class $path")
        bytes.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, candidate: String, descriptor: String,
                                         signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (candidate != name && !(name.endsWith("*") && candidate.startsWith(name.dropLast(1)))) return null
                    val result = Method().also(found::add)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, called: String,
                                                     descriptor: String, isInterface: Boolean) {
                            result.calls += Call(owner, called)
                        }
                        override fun visitFieldInsn(opcode: Int, owner: String, field: String, descriptor: String) {
                            result.fields += "$owner.$field"
                        }
                        override fun visitInsn(opcode: Int) {
                            if (opcode == Opcodes.ARETURN) result.objectReturns++
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        return found
    }

    @Test
    fun `both vanilla overloads select a complete custom query exactly once`() {
        val overloads = methods("mixins/ProjectileUtilMixin", "getEntityHitResult")
        assertEquals(2, overloads.size)
        for (overload in overloads) {
            assertEquals(1, overload.calls.count { it.name == "getEntities" })
            assertEquals(1, overload.calls.count { it.name == "requiresCustomQuery" })
            assertEquals(1, overload.calls.count { it.name == "resolveEntityHits" })
            // One return for the custom query, one for the client-side small cannon shell skip, which is decided
            // before the entity query runs. The Level overload also answers vanilla's own query from the candidates
            // it already fetched (sbw$vanillaHit), after requiresCustomQuery said no custom query is needed.
            val vanilla = overload.calls.indexOfFirst { it.name == "sbw\$vanillaHit" }
            assertEquals(if (vanilla >= 0) 3 else 2, overload.calls.count { it.name == "setReturnValue" })
            if (vanilla >= 0) assertTrue(vanilla > overload.calls.indexOfFirst { it.name == "requiresCustomQuery" })
            val skip = overload.calls.indexOfFirst { it.name == "sbw\$clientShellWithoutEntityHits" }
            assertTrue(skip >= 0 && skip < overload.calls.indexOfFirst { it.name == "getEntities" })
            assertFalse(overload.calls.any { it.name in setOf("nearestObb", "clipProjectile", "emitObbHitEffects") })
        }
        assertEquals(1, overloads.count { overload -> overload.calls.any { it.name == "sbw\$vanillaHit" } })
        // The vanilla answer defers to vanilla's own query whenever a vehicle is among the candidates.
        val vanillaHit = methods("mixins/ProjectileUtilMixin", "sbw\$vanillaHit").single()
        assertTrue(vanillaHit.calls.any { it.name == "intersects" } && vanillaHit.calls.any { it.name == "clip" })
        val query = methods("mixins/ProjectileUtilMixin", "resolveEntityHits").single()
        for (name in listOf("getOwner", "getPassengers", "getRootVehicle", "canRiderInteract",
            "clipProjectile", "isNarrowAtgm", "preciseInterceptionHitPoint", "nearestObb", "intersects", "consider")) {
            assertTrue(query.calls.any { it.name == name }, name)
        }
        val consider = query.calls.indexOfFirst { it.name == "consider" }
        val effect = query.calls.indexOfFirst { it.name == "emitObbHitEffects" }
        val metadata = query.calls.indexOfFirst { it.name == "sbw\$setProjectileContact" }
        assertTrue(metadata > consider && effect > metadata)
        assertEquals(2, query.objectReturns, "Only final miss/final nearest hit may return; no early OBB winner")
        assertTrue(methods("mixins/ProjectileUtilMixin", "usesDetailedTarget").single().fields.any { it.endsWith(".isClientSide") })
    }

    @Test
    fun `bullet collection is read only and carries per-hit metadata through the result`() {
        val capture = methods("entity/projectile/ProjectileEntity", "getHitResult").single()
        assertTrue(capture.calls.any { it == Call("${root}world/phys/ProjectileHitSelection", "nearestObb") })
        assertTrue(capture.calls.any { it.name == "preciseInterceptionHitPoint" })
        assertFalse(capture.calls.any { it.name in setOf("sbw\$setCurrentHitPart", "playSound") })
        assertTrue(capture.calls.any { it.owner == "${root}world/phys/EntityResult" && it.name == "<init>" })
        val extended = methods("world/phys/ExtendedEntityRayTraceResult", "<init>").single()
        assertTrue(extended.calls.any { it.name == "getHitPart" })
        assertTrue(extended.fields.any { it.endsWith(".hitPart") })
        val hit = methods("entity/projectile/ProjectileEntity", "onHit").single()
        val readPart = hit.calls.indexOfFirst { it.name == "getHitPart" }
        val setPart = hit.calls.indexOfFirst { it.name == "sbw\$setProjectileContact" }
        val resolver = hit.calls.indexOfFirst { it.owner == "${root}api/projectile/impact/ProjectileImpactResolver" }
        assertTrue(readPart >= 0 && setPart > readPart && resolver > setPart)
    }

    @Test
    fun `bullet tick orders after block truncation and preserves stop versus pass decisions`() {
        val tick = methods("entity/projectile/ProjectileEntity", "tick").single()
        val collect = tick.calls.indexOfFirst { it.name == "findEntitiesOnPath" }
        val sort = tick.calls.indexOfFirst { it.name == "sortFromStart" }
        val traversal = tick.calls.indexOfFirst { it.name == "visit" }
        assertTrue(collect >= 0 && sort > collect && traversal > sort)
        val callbacks = methods("entity/projectile/ProjectileEntity", "tick\$lambda*").flatMap { it.calls }
        assertTrue(callbacks.any { it.name == "onHit" })
        assertTrue(callbacks.any { it.name == "takeLastImpactStopsTraversal" })
        assertTrue(tick.calls.take(collect).any { it.name == "getLocation" })
        assertTrue(callbacks.any { it.name == "takeLastImpactPassed" })
        val hit = methods("entity/projectile/ProjectileEntity", "onHit").single()
        assertTrue(hit.calls.any { it.name == "consumesProjectile" })
        assertTrue(hit.calls.any { it.name == "continuesDefaultPipeline" })
        for (name in listOf("lastImpactPassed", "lastImpactStopsTraversal")) {
            assertTrue(hit.fields.any { it.endsWith(".$name") })
        }
    }

    @Test
    fun `optional TacZ clips nearest OBB but retains detailed final miss and its native effect gate`() {
        val query = methods("mixins/tacz/EntityUtilMixin", "getHitResult").single()
        val detailed = query.calls.indexOfFirst { it.name == "clipProjectile" }
        val nearest = query.calls.indexOfFirst { it.name == "nearestObb" }
        val profile = query.calls.indexOfFirst { it.name == "profileId" }
        assertTrue(detailed >= 0 && nearest > detailed && profile > nearest)
        assertEquals(2, query.calls.count { it.name == "setReturnValue" })
        assertEquals(1, query.calls.count { it.name == "playSound" })
        assertFalse(query.calls.any { it.name in setOf("hurt", "discard", "resolve", "sbw\$setCurrentHitPart") })
    }

    @Test
    fun `every TacZ OBB query exit cancels the vanilla AABB fallback including a miss`() {
        data class Step(val call: String? = null, val target: Label? = null,
                        val unconditional: Boolean = false, val returns: Boolean = false)
        val steps = mutableListOf<Step>()
        val labels = mutableMapOf<Label, Int>()
        javaClass.classLoader.getResourceAsStream("${root}mixins/tacz/EntityUtilMixin.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String,
                                         signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name != "getHitResult") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLabel(label: Label) { labels[label] = steps.size }
                        override fun visitJumpInsn(opcode: Int, label: Label) {
                            steps += Step(target = label, unconditional = opcode == Opcodes.GOTO)
                        }
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                     descriptor: String, isInterface: Boolean) { steps += Step(call = name) }
                        override fun visitInsn(opcode: Int) {
                            if (opcode == Opcodes.RETURN) steps += Step(returns = true)
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        val start = steps.indexOfFirst { it.call == "nearestObb" }
        assertTrue(start >= 0)
        val pending = ArrayDeque<Pair<Int, Boolean>>()
        val seen = mutableSetOf<Pair<Int, Boolean>>()
        pending.add(start + 1 to false)
        while (pending.isNotEmpty()) {
            val state = pending.removeFirst()
            if (!seen.add(state)) continue
            val step = steps[state.first]
            val cancelled = state.second || step.call == "setReturnValue"
            if (step.returns) {
                assertTrue(cancelled, "OBB miss must not return to the native AABB query")
                continue
            }
            if (step.target != null) pending.add(labels.getValue(step.target) to cancelled)
            if (!step.unconditional) pending.add(state.first + 1 to cancelled)
        }
    }

    @Test
    fun `native damage and TacZ dispatch consume target bound contact metadata`() {
        val commit = methods("entity/vehicle/base/VehicleDamageLifecycleService", "commit").single()
        assertTrue(commit.calls.any { it.name == "sbw\$getProjectileContact" })
        assertTrue(commit.calls.any { it.name == "partFor" })
        assertFalse(commit.calls.any { it.name == "sbw\$getCurrentHitPart" })
        val dispatch = methods("mixins/tacz/EntityKineticBulletMixin", "sbw\$entityImpact").single()
        val publish = dispatch.calls.indexOfFirst { it.name == "sbw\$setProjectileContact" }
        val damage = dispatch.calls.indexOfFirst { it.name == "resolve" }
        assertTrue(publish >= 0 && damage > publish)
    }
}
