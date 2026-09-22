package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.send.PlayerStopRidingMessage
import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityMountEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.util.UUID
import java.util.WeakHashMap

/** Server edge validation has no client classes and retains no strong player/connection references. */
@Mod.EventBusSubscriber
object VehicleDismountServer {
    private data class Context(val playerUuid: UUID, val playerId: Int, val vehicleId: Int,
        val vehicleUuid: UUID, val dimension: ResourceLocation, val seat: Int)

    private val sessions = WeakHashMap<Connection, VehicleDismountAdmission<Context>>()

    private fun current(player: ServerPlayer): Context? {
        if (!player.isAlive || player.isRemoved || player.isSpectator ||
            player.connection.player !== player || !player.connection.connection.isConnected) return null
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        val seat = vehicle.getSeatIndex(player)
        if (vehicle.isRemoved || vehicle.level() !== player.level() || seat !in 0..2047 ||
            vehicle.getNthEntity(seat) !== player) return null
        return Context(player.uuid, player.id, vehicle.id, vehicle.uuid,
            player.level().dimension().location(), seat)
    }

    @JvmStatic
    fun admit(player: ServerPlayer, message: PlayerStopRidingMessage): Boolean {
        val current = current(player)
        val claimed = Context(player.uuid, player.id, message.vehicleId, message.vehicleUuid,
            message.dimension, message.seatIndex)
        val admission = sessions.getOrPut(player.connection.connection) { VehicleDismountAdmission() }
        return admission.accept(claimed, current, message.sequence, message.edge, System.nanoTime()) ==
            VehicleDismountGestureResult.COMPLETED
    }

    @JvmStatic
    fun invalidateDismountGesture(entity: net.minecraft.world.entity.Entity) {
        val player = entity as? ServerPlayer ?: return
        sessions[player.connection.connection]?.clearGesture()
    }

    @JvmStatic
    @SubscribeEvent
    fun tick(event: TickEvent.PlayerTickEvent) {
        val player = event.player as? ServerPlayer ?: return
        if (event.phase == TickEvent.Phase.END) sessions[player.connection.connection]?.validate(
            current(player), System.nanoTime())
    }

    @JvmStatic
    @SubscribeEvent
    fun mount(event: EntityMountEvent) {
        val player = event.entityMounting as? ServerPlayer ?: return
        sessions[player.connection.connection]?.clearGesture()
    }

    @JvmStatic
    @SubscribeEvent
    fun death(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        sessions[player.connection.connection]?.clearGesture()
    }

    @JvmStatic
    @SubscribeEvent
    fun dimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        sessions[player.connection.connection]?.clearGesture()
    }

    @JvmStatic
    @SubscribeEvent
    fun logout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        sessions.remove(player.connection.connection)
    }

    @JvmStatic
    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) { sessions.clear() }
}
