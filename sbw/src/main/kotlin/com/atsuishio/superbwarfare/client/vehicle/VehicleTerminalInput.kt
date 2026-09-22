package com.atsuishio.superbwarfare.client.vehicle

import com.atsuishio.superbwarfare.Mod
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.lwjgl.glfw.GLFW
import java.lang.reflect.Method

/** FFA owns capability checks, the request packet and the authorized terminal session. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object VehicleTerminalInput {
    private data class Api(val canOpen: Method, val requestOpen: Method)
    private var warned = false
    private fun warn(failure: Throwable) {
        if (!warned) { warned = true; Mod.LOGGER.warn("FFA vehicle terminal integration unavailable", failure) }
    }
    private val api: Api? by lazy {
        try {
            val type = Class.forName("dev.ballistics.client.VehicleTerminalClient")
            Api(type.getMethod("canOpen", Player::class.java), type.getMethod("requestOpen"))
        } catch (_: ClassNotFoundException) { null }
        catch (failure: ReflectiveOperationException) { warn(failure); null }
        catch (failure: LinkageError) { warn(failure); null }
    }

    fun controlsActive(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (mc.screen != null || !mc.isWindowActive || !player.isAlive || player.isSpectator || !player.isPassenger) return false
        val bridge = api ?: return false
        return try { bridge.canOpen.invoke(null, player) == true }
        catch (failure: ReflectiveOperationException) { warn(failure); false }
        catch (failure: LinkageError) { warn(failure); false }
    }

    private fun press(input: InputConstants.Key, action: Int): Boolean {
        if (action != GLFW.GLFW_PRESS || !VehicleTerminalKeys.OPEN.isActiveAndMatches(input)) return false
        return try { api?.requestOpen?.invoke(null); true }
        catch (failure: ReflectiveOperationException) { warn(failure); false }
        catch (failure: LinkageError) { warn(failure); false }
    }

    // Ordinary item keys first see the active mapping and yield; opening uses a server request.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun key(event: InputEvent.Key) { press(InputConstants.getKey(event.key, event.scanCode), event.action) }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun mouse(event: InputEvent.MouseButton.Pre) {
        if (press(InputConstants.Type.MOUSE.getOrCreate(event.button), event.action)) event.isCanceled = true
    }
}
