package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

/** Compiled route/control-flow contract; this does not simulate a server or a launched store. */
class AircraftArmamentFireInterlockTest {
    private data class Call(val owner: String, val name: String, val descriptor: String)
    private data class Instruction(val opcode: Int, val call: Call? = null, val target: Label? = null)
    private class Trace {
        val code = mutableListOf<Instruction>()
        val labels = mutableMapOf<Label, Int>()
    }

    private fun methods(): Map<String, Trace> {
        val result = mutableMapOf<String, Trace>()
        val resource = "/com/atsuishio/superbwarfare/api/aircraft/AircraftArmamentManager.class"
        val bytes = javaClass.getResourceAsStream(resource)!!.use { it.readBytes() }
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String,
                                     signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val trace = result.getOrPut(name) { Trace() }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                 descriptor: String, isInterface: Boolean) {
                        trace.code += Instruction(opcode, Call(owner, name, descriptor))
                    }
                    override fun visitInsn(opcode: Int) { trace.code += Instruction(opcode) }
                    override fun visitJumpInsn(opcode: Int, label: Label) {
                        trace.code += Instruction(opcode, target = label)
                    }
                    override fun visitLabel(label: Label) { trace.labels[label] = trace.code.size }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return result
    }

    private fun terminalAfterGate(trace: Trace, allowed: Boolean): String {
        val gate = trace.code.indexOfFirst { it.call?.name == "isVehicleActionFireAllowed" }
        assertTrue(gate >= 0, "The common store boundary must consult the live vehicle interlock")
        assertEquals(0, trace.code.take(gate).count { it.call != null },
            "No lookup, state mutation or launch may precede the common gate")
        val branchIndex = (gate + 1 until trace.code.size).first {
            trace.code[it].opcode == Opcodes.IFEQ || trace.code[it].opcode == Opcodes.IFNE
        }
        val branch = trace.code[branchIndex]
        val jump = if (branch.opcode == Opcodes.IFNE) allowed else !allowed
        var index = if (jump) trace.labels.getValue(branch.target!!) else branchIndex + 1
        repeat(trace.code.size) {
            val instruction = trace.code[index]
            if (instruction.opcode == Opcodes.ATHROW) return "reject"
            if (instruction.call?.name == "definition") return "definition"
            if (instruction.opcode == Opcodes.GOTO) {
                index = trace.labels.getValue(instruction.target!!)
            } else {
                val call = instruction.call
                assertTrue(call == null || call.name == "<init>" || call.name == "toString",
                    "Unexpected operation before admission: $call")
                index++
            }
        }
        fail<Unit>("No admission terminal found")
        return "unreachable"
    }

    @Test fun `blocked direct release rejects before any hardpoint or mutable state lookup`() {
        assertEquals("reject", terminalAfterGate(methods().getValue("fire"), false))
    }

    @Test fun `allowed release continues to the established hardpoint validation`() {
        assertEquals("definition", terminalAfterGate(methods().getValue("fire"), true))
    }

    @Test fun `direct requests and scheduled stores share the guarded boundary`() {
        val methods = methods()
        for (route in listOf("handle", "tryFireStore")) {
            val calls = methods.getValue(route).code.mapNotNull { it.call }
            assertTrue(calls.any { it.owner.endsWith("/AircraftArmamentManager") && it.name == "fire" }, route)
            assertFalse(calls.any { it.name == "launch" }, "$route must not launch outside the common boundary")
        }
    }
}
