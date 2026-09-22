package com.atsuishio.superbwarfare.architecture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

class CompiledDependenciesTest {
    private fun fixture(configure: (ClassWriter) -> Unit): Set<String> {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Subject", null, "java/lang/Object", null)
        configure(writer)
        writer.visitEnd()
        return CompiledDependencies.read(writer.toByteArray())
    }

    @Test
    fun `field and method descriptors include arrays return values generics and exceptions`() {
        val refs = fixture { writer ->
            writer.visitField(0, "values", "[Lhidden/ArrayValue;", null, null).visitEnd()
            writer.visitField(0, "generic", "Ljava/util/List;", "Ljava/util/List<Lhidden/GenericOnly;>;", null).visitEnd()
            writer.visitMethod(0, "read", "(Lhidden/Argument;)Lhidden/Result;", null, arrayOf("hidden/Failure")).visitEnd()
        }
        assertTrue(refs.containsAll(listOf("hidden/ArrayValue", "hidden/GenericOnly", "hidden/Argument", "hidden/Result", "hidden/Failure")), refs.toString())
    }

    @Test
    fun `instruction owners casts class literals and annotation values cannot hide a dependency`() {
        val refs = fixture { writer ->
            writer.visitAnnotation("Lhidden/Annotation;", false).apply {
                visit("type", Type.getObjectType("hidden/AnnotationValue"))
                visitEnum("mode", "Lhidden/Enum;", "MODE")
                visitEnd()
            }
            writer.visitMethod(0, "run", "()V", null, null).apply {
                visitCode()
                visitFieldInsn(Opcodes.GETSTATIC, "hidden/FieldOwner", "value", "I")
                visitMethodInsn(Opcodes.INVOKESTATIC, "hidden/MethodOwner", "run", "()V", false)
                visitTypeInsn(Opcodes.CHECKCAST, "hidden/Cast")
                visitLdcInsn(Type.getObjectType("hidden/Literal"))
                visitMultiANewArrayInsn("[[Lhidden/Matrix;", 2)
                visitInsn(Opcodes.RETURN)
                visitMaxs(5, 1)
                visitEnd()
            }
        }
        assertTrue(refs.containsAll(listOf("hidden/Annotation", "hidden/AnnotationValue", "hidden/Enum", "hidden/FieldOwner", "hidden/MethodOwner", "hidden/Cast", "hidden/Literal", "hidden/Matrix")), refs.toString())
    }

    @Test
    fun `method references dynamic constants and bootstrap arguments are inspected`() {
        val refs = fixture { writer ->
            writer.visitMethod(0, "run", "()V", null, null).apply {
                visitCode()
                visitInvokeDynamicInsn("run", "()Lhidden/Callback;",
                    Handle(Opcodes.H_INVOKESTATIC, "hidden/Bootstrap", "bootstrap", "()V", false),
                    Handle(Opcodes.H_INVOKESTATIC, "hidden/Target", "call", "(Lhidden/Input;)V", false),
                    Type.getMethodType("(Lhidden/MethodType;)V"))
                visitLdcInsn(ConstantDynamic("dynamic", "Lhidden/Dynamic;",
                    Handle(Opcodes.H_INVOKESTATIC, "hidden/DynamicBootstrap", "get", "()V", false),
                    Type.getObjectType("hidden/DynamicArgument")))
                visitInsn(Opcodes.RETURN)
                visitMaxs(5, 1)
                visitEnd()
            }
        }
        assertTrue(refs.containsAll(listOf("hidden/Callback", "hidden/Bootstrap", "hidden/Target", "hidden/Input", "hidden/MethodType", "hidden/Dynamic", "hidden/DynamicBootstrap", "hidden/DynamicArgument")), refs.toString())
    }

    @Test
    fun `ordinary strings are not treated as executable dependencies`() {
        val refs = fixture { writer ->
            writer.visitField(Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "message", "Ljava/lang/String;", null, "hidden/NotAType").visitEnd()
            writer.visitMethod(0, "run", "()V", null, null).apply {
                visitCode()
                visitLdcInsn("Lhidden/AlsoNotAType;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        assertFalse(refs.any { it.startsWith("hidden/") }, refs.toString())
    }

    @Test
    fun `malformed bytecode fails instead of appearing dependency free`() {
        assertThrows(Exception::class.java) { CompiledDependencies.read(byteArrayOf(0, 1, 2)) }
    }

    @Test
    fun `inheritance nested metadata and generic bounds retain their exact type identities`() {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Subject",
            "<T:Lhidden/Bound;>Lhidden/Parent;Lhidden/Contract<TT;>;",
            "hidden/Parent", arrayOf("hidden/Contract"))
        writer.visitOuterClass("hidden/Outer", null, null)
        writer.visitNestHost("hidden/Nest")
        writer.visitPermittedSubclass("hidden/Child")
        writer.visitField(0, "nested", "Lhidden/GenericOuter\$Inner;",
            "Lhidden/GenericOuter<Lhidden/OuterArgument;>.Inner<Lhidden/InnerArgument;>;", null).visitEnd()
        writer.visitEnd()
        val refs = CompiledDependencies.read(writer.toByteArray())
        assertTrue(refs.containsAll(listOf("hidden/Bound", "hidden/Parent", "hidden/Contract",
            "hidden/Outer", "hidden/Nest", "hidden/Child", "hidden/GenericOuter",
            "hidden/GenericOuter\$Inner", "hidden/OuterArgument", "hidden/InnerArgument")), refs.toString())
    }

    @Test
    fun `record annotations and catch types remain dependencies`() {
        val refs = fixture { writer ->
            writer.visitRecordComponent("value", "Lhidden/RecordValue;", null).apply {
                visitAnnotation("Lhidden/RecordAnnotation;", false).visitEnd()
                visitEnd()
            }
            writer.visitMethod(0, "run", "()V", null, null).apply {
                val start = Label()
                val end = Label()
                val handler = Label()
                visitCode()
                visitTryCatchBlock(start, end, handler, "hidden/Caught")
                visitLabel(start)
                visitInsn(Opcodes.NOP)
                visitLabel(end)
                visitInsn(Opcodes.RETURN)
                visitLabel(handler)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        assertTrue(refs.containsAll(listOf("hidden/RecordValue", "hidden/RecordAnnotation", "hidden/Caught")), refs.toString())
    }
}
