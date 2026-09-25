package com.atsuishio.superbwarfare.api.projectile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Checks critical admission/loading boundaries in compiled production, without constructing a server. */
class FarProjectileWiringTest {
    private data class Call(val owner: String, val name: String)
    private fun calls(type: String, method: String): List<Call> {
        val calls = mutableListOf<Call>()
        val path = "com/atsuishio/superbwarfare/$type.class"
        javaClass.classLoader.getResourceAsStream(path)!!.use { stream ->
            ClassReader(stream).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String,
                                         signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            calls += Call(owner, name.removeSuffix("\$default"))
                        }
                    }
                }
            }, 0)
        }
        return calls
    }

    @Test fun `supplemental path observes loaded chunks and calls whole vanilla tick without loading`() {
        val calls = calls("api/projectile/FarProjectileSimulation", "tick")
        for (required in listOf("requestProjectileSweep", "projectilePathLoaded", "advancedAt", "claim", "guardEntityTick", "flush")) {
            assertTrue(calls.any { it.name == required }, required)
        }
        val forbidden = setOf("getChunk", "getChunkFuture", "addRegionTicket", "setChunkForced", "setPos", "setDeltaMovement", "hurt")
        assertFalse(calls.any { it.name in forbidden })
        assertTrue(calls.indexOfFirst { it.name == "requestProjectileSweep" } <
            calls.indexOfFirst { it.name == "getEntity" }, "Reacquire residency before waiting for entity accessibility")
    }

    @Test fun `legacy ticket refresh is explicitly excluded in supplemental fast projectile step`() {
        val calls = calls("entity/projectile/FastThrowableProjectile", "tick")
        assertTrue(calls.any { it.name == "isSupplementalTick" })
        assertTrue(calls.any { it.name == "keepChunkLoaded" }) // Ordinary opt-in behavior is preserved.
    }

    @Test fun `ordinary boundary crossing requests residency without synchronous terrain loading`() {
        val path = calls("api/vehicle/render/FarTerrainServer", "nativeProjectilePathReady")
        for (name in listOf("requestProjectileSweep", "projectilePathLoaded"))
            assertTrue(path.any { it.name == name }, name)
        assertFalse(path.any { it.name in setOf("getChunk", "getChunkFuture", "addRegionTicket") })
        val request = calls("api/vehicle/render/FarTerrainServer", "requestProjectileSweep")
        assertTrue(request.indexOfFirst { it.name == "reserve" } < request.indexOfFirst { it.name == "projectilePathLoaded" },
            "Already loaded paths must retain leases before the ready fast path")
        val readiness = calls("api/vehicle/render/FarTerrainServer", "projectilePathLoaded")
        assertTrue(readiness.any { it.name == "ready" }, "Readiness must use shared bounded chunk observations")
    }

    @Test fun `mixin observes full server tick boundary`() {
        val calls = calls("mixins/FarProjectileServerLevelMixin", "sbw\$projectileTickBoundary")
        assertTrue(calls.any { it.name == "beforeEntityTick" })
        assertTrue(calls.any { it.name == "cancel" })
    }

    @Test fun `lifetime cleanup brackets budgeted simulation and join rejection cancels insertion`() {
        val tick = calls("api/projectile/FarProjectileSimulation", "tick")
        assertEquals(2, tick.count { it.name == "expire" || it.name == "expire\$default" })
        val expiry = calls("api/projectile/FarProjectileSimulation", "expire")
        assertTrue(expiry.any { it.name == "farProjectileTerminatesNextTick" })
        assertTrue(expiry.any { it.name == "discard" })
        val joined = calls("api/projectile/FarProjectileSimulation", "joined")
        assertTrue(joined.any { it.name == "setCanceled" })
    }

    @Test fun `prediction pause is initialized on registration and published after actual far work`() {
        val owner = "com/atsuishio/superbwarfare/api/projectile/FarProjectileTracking"
        fun pauseCalls(calls: List<Call>) = calls.count { it.owner == owner && it.name == "pause" }
        assertEquals(1, pauseCalls(calls("api/projectile/FarProjectileSimulation", "register")))
        val native = calls("api/projectile/FarProjectileSimulation", "beforeEntityTick")
        assertEquals(2, pauseCalls(native))
        assertTrue(native.indexOfFirst { it.name == "hasProjectileCorridors" } <
            native.indexOfFirst { it.name == "chunks" }, "Disabled far render must not allocate native projectile corridors")
        assertTrue(native.indexOfFirst { it.name == "nativeProjectilePathReady" } <
            native.indexOfFirst { it.name == "ordinary" }, "Unready terrain cannot consume an ordinary simulation tick")
        val tick = calls("api/projectile/FarProjectileSimulation", "tick")
        assertEquals(1, pauseCalls(tick), "Only one end-of-tick pause publication site")
        val paused = tick.indexOfLast { it.owner == owner && it.name == "pause" }
        assertTrue(paused > tick.indexOfLast { it.name == "guardEntityTick" })
        assertTrue(paused > tick.indexOfLast { it.name == "expire" || it.name == "expire\$default" })
        assertTrue(tick.subList(0, paused).any { it.name == "advancedAt" },
            "Final publication must include both ordinary boundary crossing and supplemental advancement")
        assertTrue(tick.subList(0, paused).any { it.name == "awaitingFirstTick" },
            "A round fired during entity iteration must not be held at the muzzle before its first tick")
    }

    @Test fun `ballistic rounds never publish a residency hold and clients ignore one`() {
        val pause = calls("api/projectile/FarProjectileTracking", "pause")
        val free = pause.indexOfFirst { it.name == "holdsFreeFlight" }
        val send = pause.indexOfFirst { it.name == "sendPacketToTrackingThis" }
        assertTrue(free >= 0 && send > free)
        val apply = calls("client/FarProjectilePlayback", "apply")
        assertTrue(apply.any { it.name == "holdsFreeFlight" })
    }

    @Test fun `projectile broadphase no longer streams the whole level`() {
        val query = calls("mixins/LevelMixin", "getEntities")
        assertFalse(query.any { it.name in setOf("getAll", "stream", "spliterator") })
        assertTrue(query.any { it.name == "candidates" })
        assertTrue(query.indexOfFirst { it.name == "mayOverlap" } < query.indexOfFirst { it.name == "collides" })
    }
}
