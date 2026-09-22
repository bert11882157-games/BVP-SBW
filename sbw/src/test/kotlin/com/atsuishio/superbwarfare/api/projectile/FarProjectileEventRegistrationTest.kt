package com.atsuishio.superbwarfare.api.projectile

import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.BusBuilder
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.EventListenerHelper
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.lang.reflect.Modifier

class FarProjectileEventRegistrationTest {
    object StaticHandlerControl {
        var calls = 0
        @JvmStatic @SubscribeEvent fun stopped(event: ServerStoppedEvent) { calls++ }
    }

    @Test fun `client level leave returns before touching integrated server registry`() {
        // ClientLevel construction requires a running client. Inspect the compiled branch that
        // protects the real registry instead: a false ServerLevel test must return immediately.
        val node = ClassNode()
        javaClass.classLoader.getResourceAsStream(
            "com/atsuishio/superbwarfare/api/projectile/FarProjectileSimulation.class")!!.use {
            ClassReader(it).accept(node, 0)
        }
        val instructions = node.methods.single { it.name == "left" }.instructions.toArray().toList()
        val guard = instructions.indexOfFirst {
            it is TypeInsnNode && it.opcode == Opcodes.INSTANCEOF && it.desc == "net/minecraft/server/level/ServerLevel"
        }
        val removal = instructions.indexOfFirst { it is MethodInsnNode && it.name == "remove" }
        assertTrue(guard >= 0 && removal > guard, "ServerLevel guard must precede map removal")
        val branch = instructions.drop(guard + 1).first { it.opcode >= 0 } as JumpInsnNode
        var clientPath = when (branch.opcode) {
            Opcodes.IFNE -> branch.next
            Opcodes.IFEQ -> branch.label
            else -> error("Expected a boolean ServerLevel guard")
        }
        while (clientPath.opcode < 0) clientPath = clientPath.next
        assertEquals(Opcodes.RETURN, clientPath.opcode,
            "Non-server leave must return before an equal network id can remove the server projectile")
    }

    @Test fun `real Forge bus dispatches production Kotlin object handlers registered as KFF INSTANCE`() {
        val service = FarProjectileSimulation
        val type = service.javaClass
        val handlers = type.declaredMethods.filter { it.isAnnotationPresent(SubscribeEvent::class.java) }
        assertEquals(setOf("joined", "left", "stopped", "tick"), handlers.map { it.name }.toSet())
        handlers.forEach { assertFalse(Modifier.isStatic(it.modifiers), "KFF object handler ${it.name}") }
        for (name in listOf("beforeEntityTick", "isSupplementalTick")) {
            assertTrue(Modifier.isStatic(type.declaredMethods.single { it.name == name }.modifiers), "Java mixin API $name")
        }
        // Plain JUnit has no ModLauncher-added event constructors. Bootstrap the real parent
        // listener lists through Event's own untransformed path; registration/dispatch stays real.
        val initialize = EventListenerHelper::class.java.getDeclaredMethod("getListenerListInternal",
            Class::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun initializeEvent(type: Class<*>) {
            if (type == Event::class.java) return
            initializeEvent(type.superclass)
            initialize.invoke(null, type, true)
        }
        handlers.forEach { initializeEvent(it.parameterTypes.single()) }
        val bus = BusBuilder.builder().build()
        val cursor = type.getDeclaredField("cursor").apply { isAccessible = true }
        val oldCursor = cursor.getInt(null)
        try {
            cursor.setInt(null, 37)
            StaticHandlerControl.calls = 0
            // Exact KFF registration shape, deliberately not register(service.javaClass).
            bus.register(service)
            bus.register(StaticHandlerControl)
            bus.post(ServerStoppedEvent(null))
            assertEquals(0, cursor.getInt(null), "Production stop handler must run on the real event bus")
            assertEquals(0, StaticHandlerControl.calls, "The old static-on-instance form must be a failing control")
        } finally {
            bus.unregister(service)
            bus.unregister(StaticHandlerControl)
            cursor.setInt(null, oldCursor)
        }
    }
}
