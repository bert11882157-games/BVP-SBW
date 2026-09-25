package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.mixins.FarProjectileChunkMapAccessor
import com.atsuishio.superbwarfare.mixins.FarProjectileTrackedEntityAccessor
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.server.level.ServerPlayer
import com.atsuishio.superbwarfare.network.message.receive.FarProjectileStateMessage
import com.atsuishio.superbwarfare.tools.sendPacketToTrackingThis
import com.atsuishio.superbwarfare.tools.sendPacket
import java.util.WeakHashMap

/** Server-thread bridge to the native tracker, with one update opportunity per game tick. */
object FarProjectileTracking {
    private val sent = WeakHashMap<Entity, Long>()
    private val paused = WeakHashMap<Entity, Boolean>()

    @JvmStatic fun pause(entity: Entity, value: Boolean) {
        if (entity.isRemoved || entity !is FarProjectileAccess || entity.level() !is ServerLevel) return
        // Deterministic ballistic rounds keep flying on the client while the server waits for
        // residency; their step-aligned corrections resume sync without a hold/snap pair.
        if (holdsFreeFlight(entity)) return
        if ((paused[entity] ?: false) == value) return
        if (value) paused[entity] = true else paused.remove(entity)
        entity.sendPacketToTrackingThis(FarProjectileStateMessage(entity, value))
    }

    /** Rounds whose client copy is never held by a residency pause. */
    @JvmStatic fun holdsFreeFlight(entity: Entity): Boolean =
        entity is SmoothedBallisticProjectile && entity.smoothsBallisticFlight()

    /** Pairing follows spawn/data packets, so late observers cannot miss an earlier pause edge. */
    @JvmStatic fun paired(entity: Entity, player: ServerPlayer) {
        if (paused[entity] == true) player.sendPacket(FarProjectileStateMessage(entity, true))
    }
    @JvmStatic fun sent(entity: Entity) {
        if (entity is FarProjectileAccess) sent[entity] = entity.level().gameTime
    }

    @JvmStatic fun flush(entity: Entity) {
        val level = entity.level() as? ServerLevel ?: return
        if (entity.isRemoved || sent[entity] == level.gameTime) return
        val tracked = (level.chunkSource.chunkMap as FarProjectileChunkMapAccessor)
           .`sbw$trackedEntities`()[entity.id] as? FarProjectileTrackedEntityAccessor ?: return
        tracked.`sbw$serverEntity`().sendChanges()
    }
}
