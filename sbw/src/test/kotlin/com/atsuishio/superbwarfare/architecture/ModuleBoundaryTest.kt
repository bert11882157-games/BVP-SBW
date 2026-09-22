package com.atsuishio.superbwarfare.architecture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Typed dependency contracts for the maintained seams, not a claim that every class is decoupled. */
class ModuleBoundaryTest {
    private val root = "com/atsuishio/superbwarfare/"
    private val base = "${root}entity/vehicle/base/"
    private val standard = listOf("java/", "javax/annotation/", "kotlin/", "org/jetbrains/annotations/")

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
            ?: error("Missing compiled class: $name")

    private fun assertBoundary(roots: List<String>, allowed: (String) -> Boolean) {
        val pending = ArrayDeque(roots)
        val checked = linkedSetOf<String>()
        val failures = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!checked.add(name)) continue
            for (dependency in CompiledDependencies.read(bytes(name))) {
                val own = roots.any { dependency == it || dependency.startsWith("$it$") }
                if (own && dependency !in checked) pending.add(dependency)
                if (!own && standard.none(dependency::startsWith) && !allowed(dependency)) {
                    failures += "$name -> $dependency"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertTrue(checked.containsAll(roots))
    }

    @Test
    fun `damage transaction and access contract depend only on damage values`() {
        val values = listOf("ResolvedVehicleDamageResult", "ResolvedVehicleDamageRejection", "ResolvedVehicleModulePolicy")
        assertBoundary(listOf("${base}VehicleDamageTransaction", "${base}VehicleDamageAccess") +
            values.map { "${root}api/vehicle/damage/$it" }) { false }
    }

    @Test
    fun `weapon cache snapshot and slot state cannot reach mutable gameplay or presentation`() {
        assertBoundary(listOf("${base}VehicleWeaponStateCache", "${base}WeaponSnapshotPublisher", "${base}VehicleWeaponSlots")) { false }
    }

    @Test
    fun `ground calculation values and phases cannot receive entity world or mutable engine`() {
        val names = listOf("GroundDriveCalculator", "GroundDriveControls", "GroundDriveControlInput",
            "GroundDriveControlResult", "GroundDriveSteeringInput", "GroundDriveSteeringResult",
            "GroundDriveFinishInput", "GroundDriveFinishResult", "GroundDrivePhases")
        assertBoundary(names.map { "$base$it" }) {
            it == "net/minecraft/util/Mth" || it == "org/joml/Math"
        }
    }

    @Test
    fun `module storage reaches legacy state only through its module contract`() {
        val values = listOf("VehicleModuleAdapter", "VehicleModuleDefinition", "VehicleModuleState", "VehicleModuleIds")
        assertBoundary(listOf("${base}VehicleModuleStateService", "${base}VehicleModuleStateAccess",
            "${base}LegacyVehicleModuleState") + values.map { "${root}api/vehicle/module/$it" }) {
            it == "net/minecraft/resources/ResourceLocation" ||
                it.startsWith("net/minecraft/nbt/")
        }
    }

    @Test
    fun `shot ordering cannot resolve an entity world or client singleton directly`() {
        assertBoundary(listOf("${base}VehicleShotTransaction", "${root}api/weapon/ShotResult",
            "${root}api/weapon/ShotStatus", "${root}api/weapon/ShotRejectionReason")) {
            it == "net/minecraft/resources/ResourceLocation" || it == "net/minecraft/world/phys/Vec3"
        }
    }

    @Test
    fun `shared compiled source has no typed dependency on the vehicle pack`() {
        // Both source languages have independent class roots under Gradle. Never silently skip one.
        val sentinels = listOf("${base}VehicleEntity", "${root}api/vehicle/destruction/VehicleDestructionContext")
        val directories = sentinels.map { name ->
            val resource = javaClass.classLoader.getResource("$name.class") ?: error("Missing class: $name")
            require(resource.protocol == "file") { "Expected unpacked Gradle main classes: $resource" }
            var directory = Path.of(resource.toURI())
            repeat(name.count { it == '/' } + 1) { directory = directory.parent }
            directory.resolve(root)
        }.distinct()
        val failures = mutableListOf<String>()
        var count = 0
        for (directory in directories) {
            require(Files.isDirectory(directory)) { "Missing main class directory: $directory" }
            Files.walk(directory).use { files ->
                files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach { file ->
                    count++
                    for (dependency in CompiledDependencies.read(Files.readAllBytes(file))) {
                        if (dependency.startsWith("com/yourname/berts_vehicle_pack/")) {
                            failures += "${directory.relativize(file)} -> $dependency"
                        }
                    }
                }
            }
        }
        assertTrue(count > 0, "No main classes were inspected")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
