package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.mixins.FarProjectileChunkMapAccessor
import com.atsuishio.superbwarfare.mixins.FarProjectileTrackedEntityAccessor
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/** Snapshot before removal: native observers keep the full death FX; proxy observers get one compact burst. */
object VehicleDeathEffects {
    private data class Audience(val tick: Long, val excluded: Set<UUID>)
    private val audiences = WeakHashMap<VehicleEntity, Audience>()
    private val exclusions = ThreadLocal<Set<UUID>?>()
    private val retained = ThreadLocal<VehicleEntity?>()

    fun capture(vehicle: VehicleEntity) {
        val level = vehicle.level() as? ServerLevel ?: return
        val tracked = (level.chunkSource.chunkMap as FarProjectileChunkMapAccessor)
            .`sbw$trackedEntities`()[vehicle.id] as? FarProjectileTrackedEntityAccessor
        val native = tracked?.`sbw$seenBy`()?.mapTo(hashSetOf()) { it.player.uuid } ?: emptySet()
        val excluded = level.players().filter { FarTerrainServer.selected(it, vehicle) && it.uuid !in native }
            .mapTo(hashSetOf()) { it.uuid }
        audiences.entries.removeIf { it.key.level() === level && level.gameTime - it.value.tick > 100 }
        audiences[vehicle] = Audience(level.gameTime, excluded)
        EliteDiagnostics.record(vehicle, "far_death", "AUDIENCE", "native", native.size, "compact", excluded.size)
    }

    fun exclusionsFor(vehicle: VehicleEntity): Set<UUID> {
        if (vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE ||
            vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER) return emptySet()
        if (vehicle !in audiences) capture(vehicle)
        return audiences[vehicle]?.excluded ?: emptySet()
    }

    @JvmStatic fun allowsFullFx(player: UUID): Boolean = player !in (exclusions.get() ?: emptySet())

    fun retainedRecipient(player: net.minecraft.server.level.ServerPlayer): Boolean =
        retained.get()?.let { it.level() === player.level() && FarTerrainServer.selected(player, it) } == true

    fun withRetainedAircraft(vehicle: VehicleEntity?, action: () -> Unit) {
        val previous = retained.get()
        val aircraft = vehicle?.takeIf {
            it.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE ||
                it.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER
        }
        retained.set(aircraft)
        try { action() } finally { if (previous == null) retained.remove() else retained.set(previous) }
    }

    fun withExclusions(excluded: Set<UUID>?, action: () -> Unit) {
        val previous = exclusions.get()
        exclusions.set(excluded)
        try { action() } finally {
            if (previous == null) exclusions.remove() else exclusions.set(previous)
        }
    }
}
