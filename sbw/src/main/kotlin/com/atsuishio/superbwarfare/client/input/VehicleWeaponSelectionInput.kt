package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.client.VehicleWeaponSlotCycleClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.init.ModMobEffects
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.notInGame
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.entity.player.Player
import net.minecraftforge.client.settings.IKeyConflictContext
import org.lwjgl.glfw.GLFW

/** Number keys select primary weapons; a separately bindable key can also cycle secondary. */
object VehicleWeaponSelectionInput {
    @JvmStatic fun eligible(): Boolean {
        if (notInGame || mc.isPaused) return false
        val player = mc.player ?: return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        val seat = vehicle.getSeatIndex(player)
        return player.isAlive && !player.isRemoved && !player.isSpectator &&
            !player.hasEffect(ModMobEffects.SHOCK.get()) && !vehicle.isRemoved && !vehicle.isWreck &&
            vehicle.level() === player.level() && seat >= 0 &&
            vehicle.getNthEntity(seat) === player && vehicle.banHand(player) && vehicle.hasWeapon(seat)
    }

    @JvmStatic fun handleInput(input: InputConstants.Key, action: Int): Boolean {
        if (action != GLFW.GLFW_PRESS || !eligible() || seatModifierDown() ||
            mc.options.keyHotbarSlots.any { it.isActiveAndMatches(input) } ||
            !ModKeyMappings.VEHICLE_SWITCH_SECONDARY.isActiveAndMatches(input)) return false
        return VehicleWeaponSlotCycleClient.request(VehicleWeaponSlot.SECONDARY)
    }

    // Retained presentation hooks: primary selection no longer has a hold gesture.
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic fun holdProgress(vehicle: VehicleEntity, player: Player): Float? = null
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic fun defersPrimary(mapping: KeyMapping): Boolean = false

    /** Physical modifier read avoids recursion from the key's conflict context. */
    @JvmStatic fun seatModifierDown(): Boolean {
        val key = mc.options.keyShift.key
        return when (key.type) {
            InputConstants.Type.KEYSYM -> key != InputConstants.UNKNOWN &&
                InputConstants.isKeyDown(mc.window.window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(mc.window.window, key.value) == GLFW.GLFW_PRESS
            else -> Screen.hasShiftDown()
        }
    }
}

object VehicleWeaponSelectionKeyContext : IKeyConflictContext {
    override fun isActive(): Boolean = VehicleWeaponSelectionInput.eligible() &&
        !VehicleWeaponSelectionInput.seatModifierDown()
    override fun conflicts(other: IKeyConflictContext): Boolean = other === this
}
