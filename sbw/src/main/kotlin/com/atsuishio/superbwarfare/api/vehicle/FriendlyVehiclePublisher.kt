package com.atsuishio.superbwarfare.api.vehicle

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.FriendlyVehicleStateMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.lang.reflect.Method

/** Project Rose alone determines teams; no ownership or similarly named faction is substituted. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FriendlyVehiclePublisher {
    private data class Api(val get: Method, val teammates: Method)
    private val api: Api? by lazy {
        runCatching {
            val type = Class.forName("com.synergy902.projectrose.game.MatchManager")
            Api(type.getMethod("get", MinecraftServer::class.java),
                type.getMethod("areTeammates", ServerPlayer::class.java, ServerPlayer::class.java))
        }.getOrNull()
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || event.server.tickCount % 10 != 0) return
        val bridge = api ?: return
        val manager = runCatching { bridge.get.invoke(null, event.server) }.getOrNull() ?: return
        val players = event.server.playerList.players
        // Occupant enumeration is bounded by online players, never world entities or terrain.
        val occupied = players.mapNotNull { crew ->
            val vehicle = crew.vehicle as? VehicleEntity ?: return@mapNotNull null
            if (!crew.isAlive || crew.isSpectator || vehicle.isRemoved || vehicle.isWreck || vehicle.health <= 0) null
            else crew to vehicle
        }
        for (viewer in players) {
            val ids = linkedSetOf<String>()
            if (viewer.isAlive && !viewer.isSpectator) for ((crew, vehicle) in occupied) {
                if (viewer === crew || viewer.vehicle === vehicle || viewer.level() !== vehicle.level()) continue
                if (runCatching { bridge.teammates.invoke(manager, viewer, crew) == true }.getOrDefault(false))
                    ids.add(vehicle.uuid.toString())
                if (ids.size >= 256) break
            }
            sendPacketTo(viewer, FriendlyVehicleStateMessage(viewer.level().dimension().location().toString(), ids.toList()))
        }
    }
}
