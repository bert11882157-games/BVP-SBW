package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSnapshot
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleStore
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleHandoff
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.network.message.receive.FarVehicleFrameMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

/** Client-thread visual cache. Copies never enter ClientLevel's entity or passenger collections. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FarVehicleClient {
    val store = FarVehicleStore()
    private val handoff = FarVehicleHandoff()
    private var level: ClientLevel? = null
    private var clock = 0L
    private data class Copy(val identity: FarVehicleSnapshot, val entity: VehicleEntity)
    private val copies = HashMap<Int, Copy>()
    private val unsupportedTypes = HashSet<String>()

    fun time(partialTick: Float = 0F): Double = clock + partialTick.toDouble()

    /** Occlusion must cover the displayed handoff anchor, not only the latest physical position. */
    fun renderBounds(vehicle: VehicleEntity, partialTick: Float): net.minecraft.world.phys.AABB {
        val presentation = renderPresentation(vehicle, partialTick, vehicle.resolveChassisPresentation(partialTick))
        return vehicle.boundingBox.move(presentation.anchor.subtract(vehicle.position()))
    }

    fun clear() {
        FarEffectsClient.clear()
        FarTerrainClient.clear()
        copies.values.forEach { FarVehicleCopies.remove(it.entity) }
        copies.clear()
        unsupportedTypes.clear()
        store.clear()
        handoff.clear()
        level = null
        clock = 0L
    }

    fun currentLevel(): ClientLevel? {
        val current = Minecraft.getInstance().level
        if (current !== level) {
            clear()
            level = current
        }
        return current
    }

    /** The correction belongs to drawing only; live positions, aim and collision stay untouched. */
    fun renderPresentation(vehicle: VehicleEntity, partialTick: Float,
                           presentation: VehicleChassisPresentation): VehicleChassisPresentation {
        currentLevel() ?: return presentation
        val player = Minecraft.getInstance().player
        if (!com.atsuishio.superbwarfare.config.client.FarVehicleRenderConfig.ENABLED.get() ||
            (player != null && vehicle.hasPassenger(player))) {
            handoff.forget(vehicle.uuid)
            return presentation
        }
        val copy = FarVehicleCopies.isCopy(vehicle)
        val renderTick = time(partialTick)
        val overlap = if (!copy && presentation.mode == VehicleChassisPresentation.Mode.LEGACY &&
            vehicle.resolveVehicleFlightStrategy() == null &&
            vehicle.getVehiclePositionInterpolationSteps() > 0) {
            store.get(vehicle.id)?.takeIf {
                it.current.uuid == vehicle.uuid.toString() &&
                    it.current.type == BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type).toString() &&
                    it.current.overrideData == vehicle.override &&
                    renderTick >= it.receivedTick && renderTick - it.receivedTick <= 2.0 * it.interval
            }?.let { entry ->
                val segment = FarVehicleStore.segment(entry, renderTick)
                val a = FarVehicleStore.alpha(segment, renderTick)
                val from = segment.previous
                val to = segment.current
                FarVehicleHandoff.ClockOverlap(
                    Vec3(Mth.lerp(a.toDouble(), from.x, to.x),
                        Mth.lerp(a.toDouble(), from.y, to.y), Mth.lerp(a.toDouble(), from.z, to.z)),
                    Mth.rotLerp(a, from.yaw, to.yaw),
                    Vec3(entry.current.x, entry.current.y, entry.current.z),
                    vehicle.getVehiclePositionTarget(),
                )
            }
        } else null
        return handoff.sample(vehicle.uuid, "${vehicle.id}:${vehicle.type.descriptionId}:${vehicle.override}",
            copy, renderTick, presentation, overlap)
    }

    fun receive(message: FarVehicleFrameMessage) {
        val current = currentLevel() ?: return
        if (message.dimension != current.dimension().location().toString()) return
        if (!FarTerrainClient.acceptFrame(message.terrainToken, message.terrainRevision)) return
        if (store.accept(message.session, message.sequence, message.serverTick, message.interval,
                message.range, message.partIndex, message.partCount, message.vehicles, clock)) {
            FarTerrainClient.frameCommitted(message.terrainRevision)
            prune()
            EliteDiagnostics.recordClient(current.gameTime, "far_render", "FRAME_COMMITTED",
                "sequence", message.sequence, "vehicles", store.values().size, "range", store.rangeBlocks,
                "server_tick", message.serverTick, "receipt_tick", clock, "interval", message.interval)
        }
    }

    private fun prune() {
        val iterator = copies.iterator()
        while (iterator.hasNext()) {
            val (id, copy) = iterator.next()
            if (store.get(id)?.current?.sameIdentity(copy.identity) != true) {
                FarVehicleCopies.remove(copy.entity)
                iterator.remove()
            }
        }
    }

    /** Prefer an ordinary entity whenever available, without applying visual snapshots to it. */
    fun resolve(entry: FarVehicleStore.Entry, partialTick: Float): VehicleEntity? {
        val current = currentLevel() ?: return null
        val snapshot = entry.current
        if (snapshot.type in unsupportedTypes) return null
        val real = current.getEntity(snapshot.id)
        if (real != null) {
            copies.remove(snapshot.id)?.let { FarVehicleCopies.remove(it.entity) }
            return (real as? VehicleEntity)?.takeIf { it.uuid.toString() == snapshot.uuid }
        }
        var copy = copies[snapshot.id]
        if (copy != null && !copy.identity.sameIdentity(snapshot)) {
            FarVehicleCopies.remove(copy.entity)
            copies.remove(snapshot.id)
            copy = null
        }
        if (copy == null) {
            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation(snapshot.type)).orElse(null)
                ?: return null
            val entity = type.create(current) as? VehicleEntity ?: return null
            entity.id = snapshot.id
            entity.uuid = UUID.fromString(snapshot.uuid)
            entity.override = snapshot.overrideData
            FarVehicleCopies.register(entity)
            copy = Copy(snapshot, entity)
            copies[snapshot.id] = copy
        }
        FarVehicleCopies.apply(copy.entity, entry, time(partialTick))
        return copy.entity
    }

    fun renderFailed(type: String) {
        if (unsupportedTypes.size < FarVehicleStore.MAX_VEHICLES) unsupportedTypes.add(type)
        EliteDiagnostics.recordClient(level?.gameTime ?: clock, "far_render", "RENDER_FAILED", "type", type)
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || currentLevel() == null || Minecraft.getInstance().isPaused) return
        clock++
        FarTerrainClient.tick()
        store.expire(clock)
        prune()
    }

    @SubscribeEvent
    fun logout(event: ClientPlayerNetworkEvent.LoggingOut) { clear() }
}

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    modid = Mod.MODID, value = [Dist.CLIENT], bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
object FarVehicleReloadListener {
    @SubscribeEvent
    fun register(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener {
            Minecraft.getInstance().execute { FarVehicleClient.clear() }
        })
    }
}
