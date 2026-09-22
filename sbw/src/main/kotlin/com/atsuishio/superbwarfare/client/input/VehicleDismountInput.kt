package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.init.ModMobEffects
import com.atsuishio.superbwarfare.network.VehicleDismountEdge
import com.atsuishio.superbwarfare.network.VehicleDismountGesture
import com.atsuishio.superbwarfare.network.VehicleDismountGestureResult
import com.atsuishio.superbwarfare.network.message.send.PlayerStopRidingMessage
import com.atsuishio.superbwarfare.tools.mc
import com.atsuishio.superbwarfare.tools.notInGame
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW
import java.util.UUID

/** Exact keyboard/mouse edge owner; independent of canceled vanilla KeyMapping.isDown updates. */
@Mod.EventBusSubscriber(value = [Dist.CLIENT])
object VehicleDismountInput {
    private data class Context(
        val player: Player, val level: Level, val vehicle: VehicleEntity, val vehicleUuid: UUID,
        val seat: Int, val input: InputConstants.Key, val modifier: KeyModifier,
    )

    private var connection: ClientPacketListener? = null
    private var sequence = 0L
    private var context: Context? = null
    private val gesture = VehicleDismountGesture()

    @JvmStatic
    fun tick() {
        if (connection !== mc.connection) {
            context = null
            gesture.clear()
            connection = mc.connection
            sequence = 0L
        }
        val active = context ?: return
        if (!valid(active) || gesture.expired(System.nanoTime())) cancel()
    }

    @JvmStatic
    fun handleInput(input: InputConstants.Key, action: Int) {
        tick()
        if (action == GLFW.GLFW_RELEASE) {
            val active = context ?: return
            if (input != active.input) return
            if (gesture.accept(VehicleDismountEdge.RELEASE, System.nanoTime()) ==
                VehicleDismountGestureResult.RELEASED) send(active, VehicleDismountEdge.RELEASE)
            return
        }
        if (action != GLFW.GLFW_PRESS || !ModKeyMappings.DISMOUNT.isActiveAndMatches(input)) return
        val player = mc.player ?: return
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val active = context ?: Context(player, player.level(), vehicle, vehicle.uuid,
            vehicle.getSeatIndex(player), input, ModKeyMappings.DISMOUNT.keyModifier)
        if (!valid(active) || connection == null) return
        context = active
        when (gesture.accept(VehicleDismountEdge.PRESS, System.nanoTime())) {
            VehicleDismountGestureResult.ARMED -> {
                send(active, VehicleDismountEdge.PRESS)
                player.displayClientMessage(Component.translatable(
                    "hud.superbwarfare.dismount_double_tap"), true)
            }
            VehicleDismountGestureResult.COMPLETED -> {
                send(active, VehicleDismountEdge.PRESS)
                ClientEventHandler.stopVehicleReloadSound(player)
                context = null
            }
            else -> Unit
        }
    }

    private fun valid(active: Context): Boolean = !notInGame &&
        mc.player === active.player && mc.level === active.level &&
        active.player.level() === active.level && active.player.isAlive &&
        !active.player.isRemoved && !active.player.isSpectator &&
        !active.player.hasEffect(ModMobEffects.SHOCK.get()) &&
        active.player.vehicle === active.vehicle && !active.vehicle.isRemoved &&
        active.vehicle.uuid == active.vehicleUuid && active.vehicle.level() === active.level &&
        active.seat >= 0 && active.vehicle.getSeatIndex(active.player) == active.seat &&
        active.vehicle.getNthEntity(active.seat) === active.player &&
        ModKeyMappings.DISMOUNT.key == active.input &&
        ModKeyMappings.DISMOUNT.keyModifier == active.modifier

    private fun send(active: Context, edge: VehicleDismountEdge) {
        if (connection !== mc.connection || connection == null || sequence == Long.MAX_VALUE) return
        sendPacketToServer(PlayerStopRidingMessage(active.vehicle.id, active.vehicleUuid,
            active.vehicle.level().dimension().location(), active.seat, edge, ++sequence))
    }

    private fun cancel() {
        context?.let { send(it, VehicleDismountEdge.CANCEL) }
        context = null
        gesture.clear()
    }

    @JvmStatic
    @SubscribeEvent
    fun mount(event: EntityMountEvent) {
        if (event.entityMounting === mc.player) cancel()
    }
}
