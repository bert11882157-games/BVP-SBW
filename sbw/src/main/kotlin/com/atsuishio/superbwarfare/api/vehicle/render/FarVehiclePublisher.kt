package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.config.server.FarRenderConfig
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.FarVehicleFrameMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.server.ServerLifecycleHooks
import java.util.IdentityHashMap
import java.util.UUID
import org.slf4j.LoggerFactory

/** Publishes subscription membership; terrain depth affects drawing without changing membership. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FarVehiclePublisher {
    private const val MAX_REGISTERED_VEHICLES = 4096
    private val vehicles = FarVehicleRegistry<VehicleEntity>(MAX_REGISTERED_VEHICLES,
        removed = { it.isRemoved }, accessible = { it.level().getEntity(it.id) === it })
    private val logger = LoggerFactory.getLogger(FarVehiclePublisher::class.java)
    private var lastCaptureWarning = -1200L
    private var session = UUID.randomUUID().toString()
    private var sequence = 0L

    @SubscribeEvent
    fun loaded(event: EntityJoinLevelEvent) {
        val vehicle = event.entity as? VehicleEntity ?: return
        if (!event.level.isClientSide) {
            vehicles.joined(vehicle)
            FarTerrainServer.remember(vehicle)
        }
    }

    @SubscribeEvent
    fun unloaded(event: EntityLeaveLevelEvent) {
        val vehicle = event.entity as? VehicleEntity ?: return
        if (!event.level.isClientSide) {
            FarTerrainServer.departed(vehicle)
            vehicles.left(vehicle)
        }
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) { FarTerrainServer.clear() }

    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) {
        vehicles.clear()
        FarTerrainServer.clear()
        session = UUID.randomUUID().toString()
        sequence = 0L
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        sequence++
        val interval = FarRenderConfig.INTERVAL.get()
        val limit = FarRenderConfig.MAX_VEHICLES.get()
        val visible = vehicles.visible()
        FarTerrainServer.tick(server, visible)
        val captured = IdentityHashMap<VehicleEntity, FarVehicleSnapshot?>()
        for (player in server.playerList.players) {
            // Distribute recipients across ticks; each still receives a complete frame at its interval.
            if (Math.floorMod(sequence + player.id, interval.toLong()) != 0L) continue
            val level = player.serverLevel()
            val range = FarTerrainServer.radius(player)
            if (range == 0) continue
            val updates = if (!FarRenderConfig.ENABLED.get()) emptyList() else visible.asSequence()
                .filter { it.level() === level && !it.isRemoved && !it.isInvisible &&
                    FarTerrainServer.selected(player, it) }
                .sortedWith(compareBy<VehicleEntity> { it.distanceToSqr(player) }.thenBy { it.id })
                .take(limit)
                .mapNotNull { vehicle ->
                    com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.publishFarIfChanged(player, vehicle)
                    if (!captured.containsKey(vehicle)) {
                        captured[vehicle] = try {
                            FarVehicleCopies.capture(vehicle).takeIf { it.valid() }
                        } catch (exception: RuntimeException) {
                            if (sequence - lastCaptureWarning >= 1200) {
                                lastCaptureWarning = sequence
                                logger.warn("Unable to capture far vehicle {}", vehicle.type, exception)
                            }
                            null
                        }
                    }
                    captured[vehicle]
                }.toList()
            val selected = FarTerrainServer.frame(player, updates)
            val parts = selected.chunked(FarVehicleStore.PER_PACKET).ifEmpty { listOf(emptyList()) }
            EliteDiagnostics.record(player, "far_render", "FRAME_PUBLISHED",
                "sequence", sequence, "vehicles", selected.size, "parts", parts.size, "range", range,
                "farthest_squared", selected.lastOrNull()?.distanceSquared(player.x, player.y, player.z),
                "registered", vehicles.size, "updated", updates.size,
                "retained_without_capture", selected.size - updates.size)
            parts.forEachIndexed { index, part ->
                sendPacketTo(player, FarVehicleFrameMessage(session, level.dimension().location().toString(),
                    sequence, level.gameTime, interval, range, index, parts.size, part,
                    FarTerrainServer.token(player), FarTerrainServer.revision(player)))
            }
        }
    }
}
