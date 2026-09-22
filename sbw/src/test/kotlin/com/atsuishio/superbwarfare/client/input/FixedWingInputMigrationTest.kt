package com.atsuishio.superbwarfare.client.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW

class FixedWingInputMigrationTest {
    @Test fun `correct only the reversed A D default pair`() {
        assertTrue(FixedWingInputMigration.shouldCorrectRoll(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A, false))
        assertFalse(FixedWingInputMigration.shouldCorrectRoll(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_D, false))
        assertFalse(FixedWingInputMigration.shouldCorrectRoll(GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E, false))
        assertFalse(FixedWingInputMigration.shouldCorrectRoll(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A, true))
    }
}
