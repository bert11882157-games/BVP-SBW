package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class FarRenderWithoutTerrainTest {
    private data class Call(val owner: String, val name: String)
    private fun calls(type: String, method: String): List<Call> {
        val calls = mutableListOf<Call>()
        javaClass.classLoader.getResourceAsStream("com/atsuishio/superbwarfare/$type.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String,
                                         signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (name != method) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                     descriptor: String, isInterface: Boolean) {
                            calls += Call(owner, name)
                        }
                    }
                }
            }, 0)
        }
        assertTrue(calls.isNotEmpty(), "Missing compiled method $type.$method")
        return calls
    }

    @Test fun `vehicle and effect passes use depth without terrain preparation or visibility gates`() {
        for (type in listOf("client/renderer/FarVehicleRenderer", "client/FarEffectsClient")) {
            val render = calls(type, "render")
            assertTrue(render.any { it.name == "render" }, type)
            assertFalse(render.any { it.owner.endsWith("/FarTerrainMeshes") ||
                it.name in setOf("covers", "sections", "terrainWait") }, type)
        }
    }

    @Test fun `server keeps target residency and subscription without building or streaming cover`() {
        val tick = calls("api/vehicle/render/FarTerrainServer", "tick")
        for (required in listOf("reconcile", "addRegionTicket", "removeRegionTicket", "sendPacketTo"))
            assertTrue(tick.any { it.name == required }, required)
        assertFalse(tick.any { it.name in setOf("renderCoverage", "coverage", "getChunkFuture", "delivered") ||
            it.owner.endsWith("/FarTerrainChunk") || it.owner.endsWith("/ClientboundLevelChunkWithLightPacket") })
        val admission = calls("api/vehicle/render/FarTerrainServer", "admitsProjectileSweep")
        assertTrue(admission.any { it.owner.endsWith("/FarProjectilePolicyRange") })
        assertFalse(admission.any { it.owner.endsWith("/FarTerrainHandshake") })
    }

    @Test fun `empty terrain plan still authenticates and commits retained vehicle frames`() {
        val server = FarTerrainHandshake("session", "dimension")
        val client = FarTerrainFrameGate()
        assertTrue(server.update(960, emptySet(), emptySet()))
        assertTrue(client.plan(server.revision, 1))
        assertTrue(server.acknowledge("session", "dimension", server.revision, emptySet()))
        assertTrue(client.acceptFrame(server.revision, 2))
        client.commit(server.revision)
        assertTrue(client.ready(2000))
        assertFalse(server.update(960, emptySet(), emptySet()))
        assertFalse(client.acceptFrame(server.revision - 1, 2001))
        assertFalse(server.acknowledge("other", "dimension", server.revision, emptySet()))
    }

    @Test fun `projectile paths no longer require a visual terrain subscription and remain bounded`() {
        val from = Vec3(800.0, 70.0, 0.0)
        val to = Vec3(900.0, 70.0, 0.0)
        val path = setOf(FarTerrainPolicy.key(50, 0), FarTerrainPolicy.key(56, 0))
        assertTrue(FarProjectilePolicyRange.contains(0.0, 0.0, 960, from, to, path))
        assertFalse(FarProjectilePolicyRange.contains(0.0, 0.0, 960, from, Vec3(961.0, 70.0, 0.0), path))
        assertFalse(FarProjectilePolicyRange.contains(0.0, 0.0, 960, from, to, emptySet()))
        assertFalse(FarProjectilePolicyRange.contains(0.0, 0.0, 960, from, to, (0L..256L).toSet()))
        assertFalse(FarProjectilePolicyRange.contains(0.0, 0.0, 960, from, Vec3(900.0, Double.NaN, 0.0), path))
    }

    @Test fun `absent terrain has ambient sky light without artificial block light`() {
        assertEquals(15, FarVehicleLighting.unloaded(true, true, 64, -64))
        assertEquals(0, FarVehicleLighting.unloaded(false, true, 64, -64))
        assertEquals(0, FarVehicleLighting.unloaded(true, false, 64, -64))
        assertEquals(0, FarVehicleLighting.unloaded(true, true, -65, -64))
    }
}
