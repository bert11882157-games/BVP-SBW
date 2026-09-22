package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.input.VehicleControlBindings
import com.atsuishio.superbwarfare.client.input.VehicleKeyMapping
import com.mojang.blaze3d.platform.InputConstants
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.lwjgl.glfw.GLFW

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID,
    value = [Dist.CLIENT], bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
object AircraftArmamentKeys {
    private val context = object : IKeyConflictContext {
        override fun isActive() = AircraftArmamentClient.controlsActive()
        override fun conflicts(other: IKeyConflictContext) = other === this
    }
    private fun key(name: String, code: Int) = VehicleControlBindings.register(VehicleKeyMapping(
        "key.superbwarfare.aircraft_$name", KeyModifier.NONE, InputConstants.Type.KEYSYM, code,
        VehicleControlBindings.PLANE_CATEGORY, context))

    @JvmField val OPEN = key("loadout", GLFW.GLFW_KEY_I)
    @JvmField val POD = key("pod", GLFW.GLFW_KEY_P)
    @JvmField val DESIGNATE = VehicleControlBindings.register(VehicleKeyMapping(
        "key.superbwarfare.aircraft_designate", KeyModifier.NONE, InputConstants.Type.MOUSE,
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE, VehicleControlBindings.PLANE_CATEGORY, context))
    @JvmField val CLEAR = key("clear_designation", GLFW.GLFW_KEY_U)
    @JvmField val FIRE = key("fire_store", GLFW.GLFW_KEY_L)
    @JvmField val CYCLE = key("cycle_store", GLFW.GLFW_KEY_RIGHT_BRACKET)
    @JvmField val POD_PITCH_DOWN = ModKeyMappings.FIXED_WING_PITCH_DOWN
    @JvmField val POD_PITCH_UP = ModKeyMappings.FIXED_WING_PITCH_UP
    @JvmField val POD_ROLL_LEFT = ModKeyMappings.FIXED_WING_ROLL_LEFT
    @JvmField val POD_ROLL_RIGHT = ModKeyMappings.FIXED_WING_ROLL_RIGHT
    @JvmField val flightMappings = arrayOf(POD_PITCH_DOWN, POD_PITCH_UP, POD_ROLL_LEFT, POD_ROLL_RIGHT)
    private val all = arrayOf(OPEN, POD, DESIGNATE, CLEAR, FIRE, CYCLE)

    @SubscribeEvent fun register(event: RegisterKeyMappingsEvent) = all.forEach(event::register)
    @SubscribeEvent fun reload(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(net.minecraft.server.packs.resources.ResourceManagerReloadListener {
            net.minecraft.client.Minecraft.getInstance().execute {
                AircraftStoreItemRenderer.clear()
                AircraftSeekerHud.reset()
            }
        })
    }
    @SubscribeEvent fun overlays(event: RegisterGuiOverlaysEvent) {
        event.registerAboveAll("aircraft_armament") { _, graphics, partial, width, height ->
            AircraftArmamentClient.renderHud(graphics, partial, width, height)
        }
    }
}
