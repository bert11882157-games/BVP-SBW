package com.atsuishio.superbwarfare.client.vehicle

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.input.VehicleControlBindings
import com.atsuishio.superbwarfare.client.input.VehicleKeyMapping
import com.mojang.blaze3d.platform.InputConstants
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.lwjgl.glfw.GLFW

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID,
    value = [Dist.CLIENT], bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
object VehicleTerminalKeys {
    private val context = object : IKeyConflictContext {
        override fun isActive() = VehicleTerminalInput.controlsActive()
        override fun conflicts(other: IKeyConflictContext) = other === this
    }
    @JvmField val OPEN = VehicleControlBindings.register(VehicleKeyMapping(
        "key.superbwarfare.vehicle_terminal", KeyModifier.NONE, InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_H, VehicleControlBindings.BVP_CATEGORY, context))

    @SubscribeEvent fun register(event: RegisterKeyMappingsEvent) = event.register(OPEN)
}
