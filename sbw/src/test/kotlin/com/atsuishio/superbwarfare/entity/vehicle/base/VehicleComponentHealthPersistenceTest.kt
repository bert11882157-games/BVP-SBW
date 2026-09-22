package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VehicleComponentHealthPersistenceTest {
    @Test fun `empty summon NBT uses each component maximum`() {
        val tag = CompoundTag()
        for (component in listOf("Turret", "LeftWheel", "RightWheel", "MainEngine", "SubEngine")) {
            assertEquals(50F, componentHealthOrDefault(tag, "${component}Health", "${component}Damaged", 50F))
            assertEquals(120F, componentHealthOrDefault(tag, "${component}Health", "${component}Damaged", 120F))
        }
    }

    @Test fun `persisted zero and partial damage are never healed on load`() {
        val tag = CompoundTag()
        for (health in listOf(0F, 1F, 23.5F, 50F)) {
            tag.putFloat("LeftWheelHealth", health)
            assertEquals(health, componentHealthOrDefault(tag, "LeftWheelHealth", "LeftWheelDamaged", 50F))
        }
    }

    @Test fun `explicit damage flag without health still creates a broken component`() {
        val tag = CompoundTag()
        tag.putBoolean("MainEngineDamaged", true)
        assertEquals(0F, componentHealthOrDefault(tag, "MainEngineHealth", "MainEngineDamaged", 50F))
        tag.putFloat("MainEngineHealth", 12F)
        assertEquals(12F, componentHealthOrDefault(tag, "MainEngineHealth", "MainEngineDamaged", 50F))
    }
}
