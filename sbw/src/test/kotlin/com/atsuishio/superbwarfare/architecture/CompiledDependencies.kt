package com.atsuishio.superbwarfare.architecture

import org.objectweb.asm.*
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/** Reads typed class-file references; ordinary strings and debug information are not imports. */
internal object CompiledDependencies {
    fun read(bytes: ByteArray): Set<String> {
        val dependencies = linkedSetOf<String>()

        fun type(value: Type) {
            when (value.sort) {
                Type.OBJECT -> dependencies += value.internalName
                Type.ARRAY -> type(value.elementType)
                Type.METHOD -> {
                    value.argumentTypes.forEach(::type)
                    type(value.returnType)
                }
            }
        }

        fun addDescriptor(value: String?) {
            if (value != null) type(Type.getType(value))
        }

        fun addOwner(value: String?) {
            if (value == null) return
            if (value.startsWith("[")) addDescriptor(value) else dependencies += value
        }

        fun addSignature(value: String?, typeOnly: Boolean = false) {
            if (value == null) return
            fun visitor(): SignatureVisitor = object : SignatureVisitor(Opcodes.ASM9) {
                private var className = ""
                override fun visitClassType(name: String) { className = name; addOwner(name) }
                override fun visitInnerClassType(name: String) { className += "\$$name"; addOwner(className) }
                override fun visitArrayType() = visitor()
                override fun visitClassBound() = visitor()
                override fun visitInterfaceBound() = visitor()
                override fun visitSuperclass() = visitor()
                override fun visitInterface() = visitor()
                override fun visitParameterType() = visitor()
                override fun visitReturnType() = visitor()
                override fun visitExceptionType() = visitor()
                override fun visitTypeArgument(wildcard: Char) = visitor()
            }
            if (typeOnly) SignatureReader(value).acceptType(visitor())
            else SignatureReader(value).accept(visitor())
        }

        fun constant(value: Any?) {
            when (value) {
                is Type -> type(value)
                is Handle -> { addOwner(value.owner); addDescriptor(value.desc) }
                is ConstantDynamic -> {
                    addDescriptor(value.descriptor)
                    constant(value.bootstrapMethod)
                    repeat(value.bootstrapMethodArgumentCount) { constant(value.getBootstrapMethodArgument(it)) }
                }
            }
        }

        fun annotation(desc: String? = null): AnnotationVisitor {
            addDescriptor(desc)
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) = constant(value)
                override fun visitEnum(name: String?, descriptor: String, value: String) = addDescriptor(descriptor)
                override fun visitAnnotation(name: String?, descriptor: String) = annotation(descriptor)
                override fun visitArray(name: String?) = annotation()
            }
        }

        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                addOwner(superName)
                interfaces?.forEach(::addOwner)
                addSignature(signature)
            }

            override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
                addOwner(owner)
                addDescriptor(descriptor)
            }

            override fun visitNestHost(nestHost: String) = addOwner(nestHost)
            override fun visitNestMember(nestMember: String) = addOwner(nestMember)
            override fun visitPermittedSubclass(permittedSubclass: String) = addOwner(permittedSubclass)
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                addOwner(name)
                addOwner(outerName)
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean) = annotation(descriptor)
            override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)

            override fun visitRecordComponent(name: String, descriptor: String, signature: String?): RecordComponentVisitor {
                addDescriptor(descriptor)
                addSignature(signature, true)
                return object : RecordComponentVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)
                }
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
                addDescriptor(descriptor)
                addSignature(signature, true)
                constant(value)
                return object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)
                }
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                addDescriptor(descriptor)
                addSignature(signature)
                exceptions?.forEach(::addOwner)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotationDefault() = annotation()
                    override fun visitAnnotation(descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean) = annotation(descriptor)
                    override fun visitTypeInsn(opcode: Int, type: String) = addOwner(type)
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        addOwner(owner)
                        addDescriptor(descriptor)
                    }
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        addOwner(owner)
                        addDescriptor(descriptor)
                    }
                    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
                        addDescriptor(descriptor)
                        constant(bootstrapMethodHandle)
                        bootstrapMethodArguments.forEach(::constant)
                    }
                    override fun visitLdcInsn(value: Any) = constant(value)
                    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) = addDescriptor(descriptor)
                    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) = addOwner(type)
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return dependencies
    }
}
