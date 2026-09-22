package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.FixedWingPilotIntentClientStream
import com.atsuishio.superbwarfare.network.FixedWingPilotIntentKey
import com.atsuishio.superbwarfare.network.message.receive.FixedWingPilotIntentStateMessage
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.player.Player
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent

/** The marker and outgoing stream share this local target. Camera and render queries are read-only. */
@OnlyIn(Dist.CLIENT)
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FixedWingPilotIntentClient {
    private val stream = FixedWingPilotIntentClientStream()
    private var connection: ClientPacketListener? = null
    private var level: ClientLevel? = null
    private var owner: Player? = null
    private var vehicle: VehicleEntity? = null
    private var clientTick = 0L

    @JvmStatic
    fun activeView(player: Player): FixedWingPilotIntentClientStream.View? =
        if (observe(player)) stream.view() else null

    /** Queue the newest once-per-input-tick direction; this call does not send immediately. */
    @JvmStatic
    @JvmOverloads
    fun offer(player: Player, x: Double, y: Double, z: Double,
              manualMask: Int, centerAim: Boolean = false, screenRollInput: Float? = null,
              inversionRequested: Boolean = false): Boolean {
        if (!observe(player)) return false
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen != null || !minecraft.isWindowActive || !minecraft.mouseHandler.isMouseGrabbed) {
            stream.releaseManual()
            return false
        }
        return stream.offer(x, y, z, manualMask, centerAim, screenRollInput,
            minecraft.options.cameraType.isFirstPerson, inversionRequested)
    }

    @JvmStatic
    fun clearScreenGuidance(player: Player) {
        if (observe(player)) stream.clearScreenGuidance()
    }

    @JvmStatic
    fun releaseManual(player: Player) {
        if (observe(player)) stream.releaseManual()
    }

    @JvmStatic
    fun accept(message: FixedWingPilotIntentStateMessage) {
        val player = Minecraft.getInstance().player ?: return
        if (observe(player)) stream.accept(message)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        clientTick++
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        if (player == null) { observe(null); return }
        if (!observe(player)) return
        if (minecraft.screen != null || !minecraft.isWindowActive || !minecraft.mouseHandler.isMouseGrabbed) {
            stream.releaseManual()
        } else if (player.vehicle is VehicleEntity &&
            com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController.hasPresentation(player, player.vehicle as VehicleEntity)) {
            stream.releaseManual()
        } else {
            // A perspective switch changes response even when the mouse is stationary.
            stream.setFirstPerson(minecraft.options.cameraType.isFirstPerson)
        }
        stream.nextMessage(clientTick)?.let(::sendPacketToServer)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun mount(event: EntityMountEvent) {
        if (!event.isCanceled && event.entityMounting === Minecraft.getInstance().player) {
            stream.retire()
        }
    }

    private fun observe(player: Player?): Boolean {
        val minecraft = Minecraft.getInstance()
        if (connection !== minecraft.connection) {
            connection = minecraft.connection
            stream.resetConnection()
            level = null
            owner = null
            vehicle = null
            clientTick = 0L
        }
        val current = player?.vehicle as? VehicleEntity
        val eligible = connection != null && player != null && player === minecraft.player &&
            minecraft.level != null && player.level() === minecraft.level && player.isAlive && !player.isRemoved &&
            !player.isSpectator && current != null && !current.isRemoved && !current.isWreck &&
            current.level() === minecraft.level && current.getSeatIndex(player) == 0 &&
            current.getNthEntity(0) === player && current.resolveVehicleFlightStrategy() is FixedWingFlightStrategy
        if (!eligible) {
            stream.bind(null)
            level = null
            owner = null
            vehicle = null
            return false
        }
        if (level !== minecraft.level || owner !== player || vehicle !== current) stream.retire()
        level = minecraft.level
        owner = player
        vehicle = current
        stream.bind(FixedWingPilotIntentKey(player!!.uuid, current!!.id, current.uuid,
            current.level().dimension().location()))
        return true
    }
}
