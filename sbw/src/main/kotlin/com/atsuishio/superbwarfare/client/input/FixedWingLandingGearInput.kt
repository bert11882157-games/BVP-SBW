package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightActionIds
import com.atsuishio.superbwarfare.client.VehicleActionInputClient
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.lwjgl.glfw.GLFW

/** Emits one sequenced action pulse per physical press; no held action or predicted gear state. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FixedWingLandingGearInput {
    private data class Context(
        val connection: ClientPacketListener, val level: ClientLevel,
        val player: Player, val vehicle: VehicleEntity,
        val key: InputConstants.Key, val modifier: KeyModifier,
    )
    private var context: Context? = null
    private val edge = LandingGearPressEdge<InputConstants.Key>()

    private fun current(): Context? {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection ?: return null
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        if (minecraft.screen != null || !minecraft.isWindowActive || !minecraft.mouseHandler.isMouseGrabbed ||
            !player.isAlive || player.isSpectator || vehicle.isRemoved || vehicle.isWreck ||
            vehicle.level() !== level || player.level() !== level ||
            !vehicle.hasFixedWingLandingGear() ||
            vehicle.getSeatIndex(player) != 0 || vehicle.getNthEntity(0) !== player ||
            VehicleFreeCameraController.hasPresentation(player, vehicle)
        ) return null
        val mapping = ModKeyMappings.FIXED_WING_LANDING_GEAR
        return Context(connection, level, player, vehicle, mapping.key, mapping.keyModifier)
    }

    private fun clear() {
        context = null
        edge.clear()
        VehicleActionInputClient.clear(VehicleFlightActionIds.LANDING_GEAR)
    }

    private fun input(key: InputConstants.Key, action: Int) {
        if (action == GLFW.GLFW_RELEASE) { edge.release(key); return }
        if (action != GLFW.GLFW_PRESS) return
        val next = current() ?: run { clear(); return }
        if (context != next) clear()
        context = next
        if (!ModKeyMappings.FIXED_WING_LANDING_GEAR.isActiveAndMatches(key) || !edge.press(key)) return
        // The release only rearms this action's edge state; it never cancels repair or secondary fire.
        VehicleActionInputClient.sync(next.vehicle, VehicleFlightActionIds.LANDING_GEAR, true, "pilot:0")
        VehicleActionInputClient.sync(next.vehicle, VehicleFlightActionIds.LANDING_GEAR, false, "pilot:0")
    }

    @SubscribeEvent
    fun key(event: InputEvent.Key) = input(InputConstants.getKey(event.key, event.scanCode), event.action)

    @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
    fun mouse(event: InputEvent.MouseButton.Pre) = input(
        InputConstants.Type.MOUSE.getOrCreate(event.button), event.action,
    )

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END && context != current()) clear()
    }

    @SubscribeEvent
    fun screen(event: ScreenEvent.Opening) {
        if (event.newScreen != null) clear()
    }
}

/** The captured key owns release even when a modifier is released first. */
internal class LandingGearPressEdge<K> {
    private var pressed: K? = null
    fun press(key: K): Boolean {
        if (pressed != null) return false
        pressed = key
        return true
    }
    fun release(key: K) { if (pressed == key) pressed = null }
    fun clear() { pressed = null }
}
