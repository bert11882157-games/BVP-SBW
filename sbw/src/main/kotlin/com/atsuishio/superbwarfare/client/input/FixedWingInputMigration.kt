package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.config.client.PlaneControlConfig
import com.atsuishio.superbwarfare.init.ModKeyMappings
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.client.settings.KeyModifier
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.io.IOException

/** One-time user-options migration, performed only after client configuration and options are loaded. */
object FixedWingInputMigration {
    private var checked = false

    fun shouldTransfer(migrated: Boolean, newConfigured: Boolean, newIsDefault: Boolean,
                       oldKey: Int, oldHasModifier: Boolean): Boolean =
        !migrated && !newConfigured && newIsDefault &&
            (oldKey != GLFW.GLFW_KEY_S || oldHasModifier)

    fun apply(minecraft: Minecraft) {
        if (checked) return
        checked = true
        // Read legacy options before either migration saves the complete current key map.
        migrateDirectionalBindings(minecraft)
        migrateRollBindings(minecraft)
    }

    private fun migrateRollBindings(minecraft: Minecraft) {
        if (!PlaneControlConfig.ROLL_BINDINGS_CORRECTED.get()) {
            val left = ModKeyMappings.FIXED_WING_ROLL_LEFT
            val right = ModKeyMappings.FIXED_WING_ROLL_RIGHT
            if (shouldCorrectRoll(left.key.value, right.key.value,
                    left.keyModifier != KeyModifier.NONE || right.keyModifier != KeyModifier.NONE)) {
                left.setKeyModifierAndCode(KeyModifier.NONE, left.defaultKey)
                right.setKeyModifierAndCode(KeyModifier.NONE, right.defaultKey)
                KeyMapping.resetMapping()
                minecraft.options.save()
            }
            PlaneControlConfig.ROLL_BINDINGS_CORRECTED.set(true)
            PlaneControlConfig.ROLL_BINDINGS_CORRECTED.save()
        }
    }

    private fun migrateDirectionalBindings(minecraft: Minecraft) {
        if (PlaneControlConfig.DIRECTIONAL_BINDINGS_MIGRATED.get()) return
        val old = ModKeyMappings.FLIGHT_AIRBRAKE
        val current = ModKeyMappings.FIXED_WING_AIRBRAKE
        val options = minecraft.gameDirectory.toPath().resolve("options.txt")
        val configured = try {
            if (Files.isRegularFile(options)) Files.newBufferedReader(options).use { reader ->
                reader.lineSequence().take(10_000).any { it.startsWith("key_${current.name}:") }
            } else false
        } catch (_: IOException) {
            // Leave both saved bindings and the persistent migration flag unchanged; retry next launch.
            return
        }
        if (shouldTransfer(false, configured, current.isDefault, old.key.value,
                old.keyModifier != KeyModifier.NONE)) {
            current.setKeyModifierAndCode(old.keyModifier, old.key)
            KeyMapping.resetMapping()
            minecraft.options.save()
        }
        PlaneControlConfig.DIRECTIONAL_BINDINGS_MIGRATED.set(true)
        PlaneControlConfig.DIRECTIONAL_BINDINGS_MIGRATED.save()
    }

    fun shouldCorrectRoll(leftKey: Int, rightKey: Int, hasModifier: Boolean): Boolean =
        !hasModifier && leftKey == GLFW.GLFW_KEY_D && rightKey == GLFW.GLFW_KEY_A
}
