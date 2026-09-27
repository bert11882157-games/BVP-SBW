package com.atsuishio.superbwarfare.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Kotlin-for-Forge registers an `@Mod.EventBusSubscriber` Kotlin object as an instance, and the event bus then only
 * sees instance methods. `@JvmStatic` on a named object's handler compiles to a static-only method, so the handler
 * silently never runs (vehicle audio profiles, HUD layout and the flight displays were all lost this way).
 */
class KotlinObjectSubscriberTest {
    private val subscriber = "Lnet/minecraftforge/fml/common/Mod\$EventBusSubscriber;"
    private val subscribeEvent = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;"

    @Test
    fun `event subscriber objects have no static handlers`() {
        val sentinel = "com/atsuishio/superbwarfare/client/sound/vehicle/VehicleAudioProfiles"
        val resource = javaClass.classLoader.getResource("$sentinel.class") ?: error("Missing class: $sentinel")
        require(resource.protocol == "file") { "Expected unpacked Gradle main classes: $resource" }
        var directory = Path.of(resource.toURI())
        repeat(sentinel.count { it == '/' } + 1) { directory = directory.parent }
        val failures = mutableListOf<String>()
        var objects = 0
        Files.walk(directory).use { files ->
            files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach { file ->
                var annotated = false
                var kotlinObject = false
                var name = ""
                val statics = mutableListOf<String>()
                ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(version: Int, access: Int, className: String, signature: String?,
                                       superName: String?, interfaces: Array<out String>?) { name = className }

                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor == subscriber) annotated = true
                        return null
                    }

                    override fun visitField(access: Int, field: String, descriptor: String, signature: String?,
                                            value: Any?): FieldVisitor? {
                        if (field == "INSTANCE" && access and Opcodes.ACC_STATIC != 0 && descriptor == "L$name;")
                            kotlinObject = true
                        return null
                    }

                    override fun visitMethod(access: Int, method: String, descriptor: String, signature: String?,
                                             exceptions: Array<out String>?): MethodVisitor? {
                        if (access and Opcodes.ACC_STATIC == 0) return null
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                                if (annotation == subscribeEvent) statics += method
                                return null
                            }
                        }
                    }
                }, ClassReader.SKIP_CODE)
                if (annotated && kotlinObject) {
                    objects++
                    statics.forEach { failures += "$name.$it" }
                }
            }
        }
        assertTrue(objects > 0, "No subscriber objects were inspected")
        assertTrue(failures.isEmpty(), "@JvmStatic @SubscribeEvent handlers in Kotlin objects never run: $failures")
    }
}
