package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.server.ServerLifecycleHooks

/** Advances real projectiles after the terrain owner admits and asynchronously prepares their path. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FarProjectileSimulation {
    private val projectiles = LinkedHashMap<Projectile, FarProjectileTickGate>()
    private var cursor = 0
    private var supplemental: Entity? = null
    private var prefetchTick = Long.MIN_VALUE
    private var prefetchChecks = 0
    private data class PrefetchPath(val level: ServerLevel, val chunks: Set<Long>)
    private val prefetchedPaths = HashSet<PrefetchPath>()

    /** Request only a short rolling path before it is needed; never block a loaded current step
     * on an unloaded future step. The shared residency owner still caps total chunks/tickets. */
    private fun autonomousMunition(entity: Entity): Boolean =
        (entity is com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity ||
            entity is com.atsuishio.superbwarfare.entity.projectile.MissileProjectile) &&
            com.atsuishio.superbwarfare.config.server.ProjectileConfig.PROJECTILE_CHUNK_LOADING.get()

    private fun prefetch(projectile: Projectile, level: ServerLevel) {
        if (!autonomousMunition(projectile) && !FarTerrainServer.hasProjectileCorridors(level)) return
        val tick = level.server.tickCount.toLong()
        if (prefetchTick != tick) { prefetchTick = tick; prefetchChecks = 0; prefetchedPaths.clear() }
        val access = projectile as FarProjectileAccess
        val motion = projectile.deltaMovement
        for (ahead in 1..10) {
            val from = projectile.position().add(motion.scale(ahead.toDouble()))
            val to = from.add(motion)
            val chunks = FarProjectilePolicy.chunks(projectile.boundingBox.move(motion.scale(ahead.toDouble())),
                motion, access.farProjectileExplosionRadius(), 0, access.farProjectileCollisionPadding()) ?: break
            val path = PrefetchPath(level, chunks)
            if (path in prefetchedPaths) continue
            if (prefetchChecks + chunks.size > 512) break
            prefetchChecks += chunks.size
            prefetchedPaths.add(path)
            val admission = FarTerrainServer.requestProjectileSweep(level, from, to, chunks, imminent = false, guidedBomb = autonomousMunition(projectile))
            if (admission == FarTerrainServer.ProjectileAdmission.CAPACITY ||
                admission == FarTerrainServer.ProjectileAdmission.OUTSIDE_POLICY) break
        }
    }

    private fun deferred(projectile: Projectile, level: ServerLevel, reason: String) {
        if (level.gameTime % 20L == 0L && EliteDiagnostics.isServerEnabled())
            EliteDiagnostics.record(projectile, "far_projectile", "DEFERRED", "reason", reason,
                "game_time", level.gameTime, "age", projectile.tickCount, "position", projectile.position())
    }

    private fun expire(projectile: Projectile, level: ServerLevel, allowNativeTerminalTick: Boolean = false): Boolean {
        if (projectile.isRemoved) return true
        val access = projectile as FarProjectileAccess
        if (!FarProjectileLifetime.expired(projectile.persistentData, level.gameTime,
                level.dimension().location().toString(), access.farProjectileLifetimeTicks(), projectile.tickCount,
                access.farProjectileMaximumLifetimeTicks())) return false
        // A resident admitted/native final tick keeps its existing end-of-life explosion. Any deferred
        // entity that cannot actually run that tick is discarded by the unconditional end sweep.
        if (allowNativeTerminalTick && access.farProjectileTerminatesNextTick(projectile.tickCount)) return false
        EliteDiagnostics.record(projectile, "far_projectile", "EXPIRED", "game_time", level.gameTime,
            "native_lifetime", access.farProjectileLifetimeTicks(), "age", projectile.tickCount,
            "position", projectile.position(), "reason", "elapsed_lifetime_or_invalid_clock")
        projectile.discard()
        return true
    }

    private fun register(entity: Entity, allowNativeTerminalTick: Boolean = false): FarProjectileTickGate? {
        if (entity !is Projectile || entity !is FarProjectileAccess || entity.level() !is ServerLevel || entity.isRemoved) return null
        if (expire(entity, entity.level() as ServerLevel, allowNativeTerminalTick)) return null
        return projectiles[entity] ?: if (projectiles.size < FarProjectilePolicy.MAX_REGISTERED) {
            FarProjectileTickGate(entity.level().gameTime, entity.tickCount).also {
                projectiles[entity] = it
                // Initialize before native tracker pairing; subsequent registrations retain the state.
                FarProjectileTracking.pause(entity, !(entity.level() as ServerLevel).isPositionEntityTicking(entity.blockPosition()))
            }
        } else {
            EliteDiagnostics.record(entity, "far_projectile", "EXPIRED", "reason", "projectile_capacity")
            entity.discard()
            null
        }
    }

    // KotlinForForge registers an object's INSTANCE; Forge ignores static handlers on an instance.
    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        register(event.entity)
        if (event.entity is Projectile && event.entity is FarProjectileAccess &&
            event.entity.level() is ServerLevel && event.entity.isRemoved) event.isCanceled = true
    }
    @SubscribeEvent fun left(event: EntityLeaveLevelEvent) {
        if (event.entity.level() !is ServerLevel) return
        if (event.entity.isRemoved) projectiles.remove(event.entity)
    }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) {
        projectiles.clear(); supplemental = null; cursor = 0; prefetchTick = Long.MIN_VALUE; prefetchChecks = 0; prefetchedPaths.clear()
    }

    /** Called at the whole tick boundary, not a superclass tick that a subclass can continue after. */
    @JvmStatic fun beforeEntityTick(level: ServerLevel, entity: Entity): Boolean {
        if (supplemental === entity) return true
        val gate = register(entity, allowNativeTerminalTick = true)
        if (gate != null && entity is Projectile && entity is FarProjectileAccess &&
            (autonomousMunition(entity) || FarTerrainServer.hasProjectileCorridors(level))) {
            val path = FarProjectilePolicy.chunks(entity.boundingBox, entity.deltaMovement,
                entity.farProjectileExplosionRadius(), entity.farProjectileLookAheadTicks(), entity.farProjectileCollisionPadding())
            val ready = if (autonomousMunition(entity) && path != null) {
                FarTerrainServer.requestProjectileSweep(level, entity.position(), entity.position().add(entity.deltaMovement),
                    path, guidedBomb = true) == FarTerrainServer.ProjectileAdmission.READY &&
                    FarTerrainServer.projectilePathLoaded(level, path)
            } else path == null || FarTerrainServer.nativeProjectilePathReady(level, entity.position(),
                entity.position().add(entity.deltaMovement), path)
            // Warm the bounded next steps even while the current step is awaiting residency.
            // Otherwise the first cold boundary serializes current loading and future loading.
            prefetch(entity, level)
            if (path != null && !ready) {
                if (level.gameTime % 20L == 0L && EliteDiagnostics.isServerEnabled())
                    deferred(entity, level, "NATIVE_PATH_" + FarTerrainServer.requestProjectileSweep(level,
                        entity.position(), entity.position().add(entity.deltaMovement), path, guidedBomb = autonomousMunition(entity)).name)
                FarProjectileTracking.pause(entity, true)
                return false
            }
        }
        val permitted = !entity.isRemoved && (gate?.ordinary(level.gameTime) ?: true)
        if (permitted && gate != null) FarProjectileTracking.pause(entity, false)
        return permitted
    }

    /** Existing opt-in POST_TELEPORT loading is unchanged near players, disabled for this far step. */
    @JvmStatic fun isSupplementalTick(entity: Entity): Boolean = supplemental === entity

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        projectiles.keys.removeIf { it.isRemoved || it.level() !is ServerLevel }
        val snapshot = projectiles.entries.toList()
        if (snapshot.isEmpty()) { cursor = 0; return }
        // Expiry is independent of simulation admission, residency and round-robin work budgets.
        snapshot.forEach { (projectile, _) ->
            (projectile.level() as? ServerLevel)?.takeIf { it.server === server }?.let { expire(projectile, it, true) }
        }
        var advanced = 0; var checks = 0; var waiting = 0; var denied = 0
        var recovered = 0
        // Reserve one opportunity per registered shot. Catch-up work must not starve later bullets.
        val recoveryBudget = minOf(FarProjectilePolicy.MAX_RECOVERY_TICKS_PER_SERVER_TICK,
            (FarProjectilePolicy.MAX_TICKS_PER_SERVER_TICK - snapshot.size).coerceAtLeast(0))
        var visited = 0
        val start = Math.floorMod(cursor, snapshot.size)
        while (visited < snapshot.size && advanced < FarProjectilePolicy.MAX_TICKS_PER_SERVER_TICK) {
            val (projectile, gate) = snapshot[(start + visited++) % snapshot.size]
            val level = projectile.level() as? ServerLevel ?: continue
            if (level.server !== server || projectile.isRemoved || projectile.isPassenger || projectile.isVehicle) continue
            if (!projectile.canUpdate()) { deferred(projectile, level, "UPDATE_DISABLED"); continue }
            // Native movement can resume after a residency wait while still owing flight time.
            // Give it the same bounded collision-checked recovery as a far-only projectile.
            val nativeAdvanced = gate.advancedAt(level.gameTime)
            if (nativeAdvanced && !gate.hasRecoveryDebt(level.gameTime, projectile.tickCount)) continue
            var steps = if (nativeAdvanced) 1 else 0
            var simulatedHere = false
            while (steps < 4 && advanced < FarProjectilePolicy.MAX_TICKS_PER_SERVER_TICK && !projectile.isRemoved) {
                if (steps > 0 && (recovered >= recoveryBudget || !gate.hasRecoveryDebt(level.gameTime, projectile.tickCount))) break
                val access = projectile as FarProjectileAccess
                val chunks = FarProjectilePolicy.chunks(projectile.boundingBox, projectile.deltaMovement,
                    access.farProjectileExplosionRadius(), access.farProjectileLookAheadTicks(), access.farProjectileCollisionPadding())
                if (chunks == null) { deferred(projectile, level, "INVALID_SWEEP"); denied++; break }
                val from = projectile.position(); val to = from.add(projectile.deltaMovement)
                val admission = FarTerrainServer.requestProjectileSweep(level, from, to, chunks, guidedBomb = autonomousMunition(projectile))
                prefetch(projectile, level)
                if (admission != FarTerrainServer.ProjectileAdmission.READY) {
                    deferred(projectile, level, admission.name); denied++; break
                }
                checks += chunks.size
                // Both block state and entity sections must already be available; FULL alone is insufficient
                // while the asynchronous entity storage load is still completing. Never request a future.
                val loaded = FarTerrainServer.projectilePathLoaded(level, chunks)
                if (!loaded) { deferred(projectile, level, "RESIDENCY_WAIT"); waiting++; break }
                // Request the current corridor before testing accessibility. Waiting for the
                // player to make this entity accessible would defeat independent far simulation.
                if (level.getEntity(projectile.id) !== projectile) {
                    deferred(projectile, level, "ENTITY_ACCESS_WAIT"); waiting++; break
                }
                val claimed = if (steps == 0)
                    gate.claim(level.gameTime, nativeTicking = false, accessible = true, admitted = true, loaded = true)
                else gate.claimRecovery(level.gameTime, projectile.tickCount, admitted = true, loaded = true)
                if (!claimed) break
                val before = projectile.position()
                supplemental = projectile
                try {
                    level.guardEntityTick(level::tickNonPassenger, projectile)
                } finally { supplemental = null }
                advanced++; steps++
                simulatedHere = true
                if (steps > 1) recovered++
                if (EliteDiagnostics.isServerEnabled()) EliteDiagnostics.record(projectile, "far_projectile", "SIMULATED", "from", before,
                    "to", projectile.position(), "velocity", projectile.deltaMovement,
                    "chunks", chunks.size, "age", projectile.tickCount, "removed", projectile.isRemoved,
                    "recovery_step", steps > 1)
            }
            if (simulatedHere) FarProjectileTracking.flush(projectile)
        }
        cursor = (start + maxOf(1, visited)) % snapshot.size
        snapshot.forEach { (projectile, _) ->
            (projectile.level() as? ServerLevel)?.takeIf { it.server === server }?.let { expire(projectile, it) }
        }
        // Visit the complete surviving registry, including budget-skipped and newly created shots.
        // Publish one final decision per tick; successful far steps never get a pause/unpause pair.
        projectiles.entries.toList().forEach { (projectile, gate) ->
            val level = projectile.level() as? ServerLevel ?: return@forEach
            if (!projectile.isRemoved && level.server === server) {
                // Native chunks can also wait for the next swept chunk. Position residency
                // does not prove this tick ran; otherwise the client flies ahead of the server.
                FarProjectileTracking.pause(projectile, !gate.advancedAt(level.gameTime))
            }
        }
        if (server.tickCount % 20 == 0 && EliteDiagnostics.isServerEnabled()) {
            server.playerList.players.forEach { player ->
                EliteDiagnostics.record(player, "far_projectile", "SERVER_STATE", "registered", snapshot.size,
                    "simulated", advanced, "chunk_checks", checks, "waiting_loaded", waiting, "outside_corridor", denied)
            }
        }
    }
}
