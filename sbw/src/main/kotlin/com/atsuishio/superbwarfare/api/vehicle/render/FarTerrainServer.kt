package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.config.server.FarRenderConfig
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainPlan
import com.atsuishio.superbwarfare.network.message.send.FarTerrainAck
import com.atsuishio.superbwarfare.network.message.send.FarTerrainRequest
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import java.util.UUID
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/** Server-thread owner of retained vehicle subscriptions and bounded projectile residency. */
object FarTerrainServer {
    private val ticket = TicketType.create<Long>("sbw_far_terrain", Comparator { a, b -> a.compareTo(b) }, 60)
    private class Session(val token: String, val level: ServerLevel, var view: Int, var touched: Long) {
        val gate = FarTerrainHandshake(token, level.dimension().location().toString())
        val revision get() = gate.revision
        val radius get() = gate.radius
        var selected: Set<UUID> = emptySet()
        val publication = FarVehiclePublication<FarVehicleSnapshot> { UUID.fromString(it.uuid) }
        var discovery: Iterator<Long> = emptyList<Long>().iterator()
        var discoveryCenter = Long.MIN_VALUE
        var lastSelection = -100L
    }
    private val sessions = HashMap<UUID, Session>()
    private val leased = HashMap<ServerLevel, MutableSet<Long>>()
    private val discoveries = HashMap<ServerLevel, FarVehicleDiscovery>()
    private data class ProjectileChunk(val level: ServerLevel, val chunk: Long)
    private val projectileResidency = FarProjectileResidency<ProjectileChunk>(
        FarTerrainResidency.PROJECTILE_CHUNKS, holdTicks = FarTerrainResidency.PROJECTILE_HOLD_TICKS)
    private val projectileReadiness = FarProjectileReadiness<ProjectileChunk>()
    private val guidedBombLeaseExpiry = HashMap<ProjectileChunk, Long>()
    private val nativeProjectileResidency = FarProjectileReadiness<ProjectileChunk>()
    private var clock = 0L

    fun request(player: ServerPlayer, request: FarTerrainRequest) {
        if (request.dimension != player.level().dimension().location().toString() ||
            request.viewChunks !in 2..32 || runCatching { UUID.fromString(request.token).toString() }.getOrNull() != request.token) return
        if (!request.enabled) { sessions.remove(player.uuid); return }
        if (FarTerrainView.parse(request.visibleVehicles, request.cameraOffsetX, request.cameraOffsetZ) == null) return
        val old = sessions[player.uuid]
        if (old == null || old.token != request.token || old.level !== player.serverLevel()) {
            sessions[player.uuid] = Session(request.token, player.serverLevel(), request.viewChunks, clock)
        } else {
            if (old.view != request.viewChunks) old.lastSelection = -100L
            old.view = request.viewChunks
            old.touched = clock
        }
    }

    fun ack(player: ServerPlayer, ack: FarTerrainAck) {
        val state = sessions[player.uuid] ?: return
        val chunks = LongOpenHashSet(ack.chunks)
        val accepted = state.level === player.serverLevel() && ack.chunks.isEmpty() &&
            chunks.size == ack.chunks.size &&
            state.gate.acknowledge(ack.token, ack.dimension, ack.revision, chunks)
        EliteDiagnostics.record(player, "far_terrain", "ACK_RECEIVED", "accepted", accepted,
            "token", ack.token, "revision", ack.revision, "current_revision", state.revision,
            "ready_chunks", ack.chunks.size, "sent_chunks", 0)
    }

    fun radius(player: ServerPlayer): Int = sessions[player.uuid]?.radius ?: 0
    fun revision(player: ServerPlayer): Long = sessions[player.uuid]?.revision ?: -1
    fun token(player: ServerPlayer): String = sessions[player.uuid]?.token ?: ""
    @JvmStatic fun admitsEffect(player: ServerPlayer, x: Double, z: Double): Boolean {
        val state = sessions[player.uuid] ?: return false
        return FarRenderConfig.ENABLED.get() && state.level === player.serverLevel() &&
            clock - state.touched <= FarTerrainPolicy.REQUEST_TTL &&
            FarTerrainPolicy.inside(player.x, player.z, x, z, state.radius)
    }

    /** Kept for effect producers; visual effects no longer acquire cover chunks. */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic fun rememberEffect(level: ServerLevel, position: net.minecraft.world.phys.Vec3, size: Double = 8.0) = Unit

    @JvmStatic fun projectileTrackingRange(player: ServerPlayer, entity: net.minecraft.world.entity.Entity,
                                           normal: Int): Int =
        if (entity is com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess &&
            projectileObserverActive(player)) maxOf(normal, PROJECTILE_VISIBILITY_RADIUS)
        // a TV munition's operator watches through its seeker however far it flies
        else if (entity.persistentData.hasUUID(com.atsuishio.superbwarfare.api.aircraft.AircraftTvGuidance.OPERATOR) &&
            entity.persistentData.getUUID(com.atsuishio.superbwarfare.api.aircraft.AircraftTvGuidance.OPERATOR) == player.uuid)
            maxOf(normal, PROJECTILE_VISIBILITY_RADIUS)
        // a carrier is seen from anywhere along its hull, not only within range of its centre
        else if (entity is com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity)
            normal + ((entity.deckSurface()?.radius ?: 0.0).toInt())
        else normal

    private fun projectileObserverActive(player: ServerPlayer): Boolean = sessions[player.uuid]?.let {
        FarRenderConfig.ENABLED.get() && it.level === player.serverLevel() &&
            clock - it.touched <= FarTerrainPolicy.REQUEST_TTL
    } == true

    const val PROJECTILE_VISIBILITY_RADIUS = 16384
    /** Server-thread observation only; does not request, renew or load a chunk. */
    internal fun ownsTerrainTicket(level: ServerLevel, chunk: Long): Boolean =
        chunk in (leased[level] ?: emptySet())

    @JvmStatic fun hasProjectileCorridors(level: ServerLevel): Boolean =
        FarRenderConfig.ENABLED.get() && sessions.values.any { it.level === level && clock - it.touched <= FarTerrainPolicy.REQUEST_TTL }

    private fun admitsProjectileSweep(level: ServerLevel, from: net.minecraft.world.phys.Vec3,
                                      to: net.minecraft.world.phys.Vec3, chunks: Set<Long>): Boolean {
        if (!FarRenderConfig.ENABLED.get() || chunks.isEmpty() ||
            chunks.size > FarTerrainResidency.PROJECTILE_CHUNKS) return false
        return sessions.any { (id, state) ->
            val player = level.server.playerList.getPlayer(id)
            state.level === level && player != null && player.serverLevel() === level &&
                clock - state.touched <= FarTerrainPolicy.REQUEST_TTL &&
                FarProjectilePolicyRange.contains(player.x, player.z, PROJECTILE_VISIBILITY_RADIUS, from, to, chunks)
        }
    }

    /** Queue bounded residency for an admitted projectile path; never synchronously load terrain. */
    @JvmStatic fun allowsProjectileSweep(level: ServerLevel, from: net.minecraft.world.phys.Vec3,
                                         to: net.minecraft.world.phys.Vec3, chunks: Set<Long>): Boolean {
        return requestProjectileSweep(level, from, to, chunks) == ProjectileAdmission.READY
    }

    enum class ProjectileAdmission { OUTSIDE_POLICY, CAPACITY, WAITING_LEASE, READY }

    /** No chunk loads; share stable observations until the server tick or ticket phase changes. */
    @JvmStatic fun projectilePathLoaded(level: ServerLevel, chunks: Set<Long>): Boolean {
        projectileReadiness.begin(level.server.tickCount.toLong(), clock)
        return chunks.all { key -> projectileReadiness.ready(ProjectileChunk(level, key)) {
            level.chunkSource.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null && level.areEntitiesLoaded(key)
        } }
    }

    @JvmStatic @JvmOverloads fun requestProjectileSweep(level: ServerLevel, from: net.minecraft.world.phys.Vec3,
                                          to: net.minecraft.world.phys.Vec3, chunks: Set<Long>, imminent: Boolean = true,
                                          guidedBomb: Boolean = false): ProjectileAdmission {
        val autonomous = guidedBomb && com.atsuishio.superbwarfare.config.server.ProjectileConfig.PROJECTILE_CHUNK_LOADING.get() &&
            chunks.isNotEmpty() && chunks.size <= FarTerrainResidency.PROJECTILE_CHUNKS
        if (!autonomous && !admitsProjectileSweep(level, from, to, chunks)) return ProjectileAdmission.OUTSIDE_POLICY
        nativeProjectileResidency.begin(level.server.tickCount.toLong(), clock)
        // Player simulation already retains its chunks. Do not spend the limited far loading
        // queue on those chunks ahead of the projectile's genuinely cold forward corridor.
        val remote = chunks.mapTo(linkedSetOf()) { ProjectileChunk(level, it) }.filterTo(linkedSetOf()) { key ->
            !nativeProjectileResidency.ready(key) {
                level.isPositionEntityTicking(net.minecraft.core.BlockPos(
                    ChunkPos.getX(key.chunk) * 16 + 8, 0, ChunkPos.getZ(key.chunk) * 16 + 8))
            }
        }
        val reserved = remote.isEmpty() || projectileResidency.reserve(remote, clock, imminent)
        if (autonomous && reserved) for (key in remote) guidedBombLeaseExpiry[key] = clock + FarTerrainResidency.PROJECTILE_HOLD_TICKS
        // Already loaded sweeps run immediately, but also retain their own lease: player movement
        // must not withdraw the chunk under a projectile at the near/far handoff.
        if (imminent && projectilePathLoaded(level, chunks)) return ProjectileAdmission.READY
        if (!reserved) return ProjectileAdmission.CAPACITY
        return if (remote.all { it.chunk in (leased[level] ?: emptySet()) }) ProjectileAdmission.READY else ProjectileAdmission.WAITING_LEASE
    }

    /** Warm far terrain before an ordinary fast projectile can step across its loaded boundary. */
    @JvmStatic fun nativeProjectilePathReady(level: ServerLevel, from: net.minecraft.world.phys.Vec3,
                                             to: net.minecraft.world.phys.Vec3, chunks: Set<Long>): Boolean {
        val admission = requestProjectileSweep(level, from, to, chunks)
        return admission == ProjectileAdmission.OUTSIDE_POLICY ||
            (admission == ProjectileAdmission.READY && projectilePathLoaded(level, chunks))
    }

    /** Visual membership survives terrain refresh and native chunk-tracking transitions. */
    fun frame(player: ServerPlayer, updates: List<FarVehicleSnapshot>): List<FarVehicleSnapshot> {
        val state = sessions[player.uuid] ?: return emptyList()
        val index = FarVehicleIndex.get(state.level)
        val selected = state.selected.filterTo(linkedSetOf()) { id ->
            val position = index.positions[id]
            val vehicle = state.level.getEntity(id)
            position != null && vehicle?.isRemoved != true && vehicle?.isInvisible != true
        }
        return state.publication.reconcile(selected, updates)
    }

    fun selected(player: ServerPlayer, vehicle: VehicleEntity): Boolean {
        val state = sessions[player.uuid] ?: return false
        return state.level === player.serverLevel() && vehicle.uuid in state.selected
    }

    fun ready(player: ServerPlayer, vehicle: VehicleEntity): Boolean = selected(player, vehicle)

    /** No terrain snapshots are cached or invalidated by the far-vehicle subscription. */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic fun changed(level: net.minecraft.world.level.Level, chunk: Long) = Unit

    fun remember(vehicle: VehicleEntity) {
        val level = vehicle.level() as? ServerLevel ?: return
        FarVehicleIndex.get(level).put(vehicle.uuid, vehicle.x, vehicle.z)
    }

    fun departed(vehicle: VehicleEntity) {
        val level = vehicle.level() as? ServerLevel ?: return
        if (vehicle.removalReason?.shouldDestroy() == true ||
            vehicle.removalReason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) {
            FarVehicleIndex.get(level).remove(vehicle.uuid)
        } else remember(vehicle)
    }

    fun tick(server: MinecraftServer, vehicles: List<VehicleEntity>) {
        clock++
        sessions.entries.removeIf { (id, state) ->
            val player = server.playerList.getPlayer(id)
            // A connected client's delayed heartbeat is not an unsubscribe. Recreating the
            // same token at revision zero would also be rejected by its retained client gate.
            player == null || player.serverLevel() !== state.level
        }
        if (clock % 10L == 0L) vehicles.forEach(::remember)
        for (player in server.playerList.players) {
            val state = sessions[player.uuid] ?: continue
            val level = state.level
            val radius = FarTerrainPolicy.radius(state.view, server.playerList.viewDistance)
            if (!FarRenderConfig.ENABLED.get()) {
                // Explicitly retire membership/terrain without resetting this token's revision.
                state.selected = emptySet()
                if (state.gate.update(radius, emptySet(), emptySet())) {
                    sendPacketTo(player, FarTerrainPlan(state.token, level.dimension().location().toString(),
                        state.revision, radius, emptyList(), emptyList()))
                }
                continue
            }
            val center = FarTerrainPolicy.key(FarTerrainPolicy.chunk(player.x), FarTerrainPolicy.chunk(player.z))
            val moved = state.discoveryCenter == Long.MIN_VALUE ||
                kotlin.math.abs(FarTerrainPolicy.x(center) - FarTerrainPolicy.x(state.discoveryCenter)) > maxOf(1, radius / 64) ||
                kotlin.math.abs(FarTerrainPolicy.z(center) - FarTerrainPolicy.z(state.discoveryCenter)) > maxOf(1, radius / 64)
            // Finish nearby sweeps while travelling; restarting at every chunk edge starves the outer ring.
            if (moved || !state.discovery.hasNext() || state.radius != radius) {
                state.discoveryCenter = center
                state.discovery = discoveryChunks(center, radius / 16).iterator()
            }
            discoveries.getOrPut(level) { FarVehicleDiscovery(level) }.pump(state.discovery, clock)
            if (clock - state.lastSelection >= 10 || radius != state.radius) {
                state.lastSelection = clock
                val index = FarVehicleIndex.get(level)
                val nearby = index.positions.entries.asSequence()
                    .filter { FarTerrainPolicy.inside(player.x, player.z, it.value.x, it.value.z, radius) }
                    .sortedWith(compareBy<Map.Entry<UUID, FarVehicleIndex.Position>> {
                        val dx = it.value.x - player.x
                        val dz = it.value.z - player.z
                        FarTerrainPolicy.selectionDistance(dx * dx + dz * dz, it.key in state.selected)
                    }.thenBy { it.key })
                    .take(FarRenderConfig.MAX_VEHICLES.get()).toList()
                val acquired = FarVehicleSubscription.reconcile(state.selected, index.positions.keys,
                    nearby.map { it.key }, FarRenderConfig.MAX_VEHICLES.get())
                state.selected = acquired.filterTo(linkedSetOf()) { id ->
                    val p = index.positions[id] ?: return@filterTo false
                    val chunk = FarTerrainPolicy.key(FarTerrainPolicy.chunk(p.x), FarTerrainPolicy.chunk(p.z))
                    if (id !in state.selected && level.areEntitiesLoaded(chunk) && level.getEntity(id) == null) {
                        index.remove(id)
                        false
                    } else true
                }
            }
            // Preserve the authenticated frame handshake, with no terrain payload or camera plan.
            if (state.gate.update(radius, emptySet(), emptySet())) {
                sendPacketTo(player, FarTerrainPlan(state.token, level.dimension().location().toString(),
                    state.revision, radius, emptyList(), emptyList()))
            }
            if (clock % 20L == 0L && EliteDiagnostics.isServerEnabled()) {
                EliteDiagnostics.record(player, "far_terrain", "SERVER_STATE", "token", state.token,
                    "revision", state.revision, "radius", radius, "selected", state.selected.size,
                    "loaded", state.selected.count { level.getEntity(it) is VehicleEntity },
                    "terrain_streaming", false, "desired_chunks", 0, "sent_chunks", 0,
                    "leased_chunks", leased[level]?.size ?: 0,
                    "projectile_reserved_chunks", projectileResidency.size,
                    "projectile_readiness_probes", projectileReadiness.probes,
                    "indexed_vehicles", FarVehicleIndex.get(level).positions.size)
            }
        }
        // Residency consists only of retained vehicle targets and short-lived projectile paths.
        val resident = HashMap<ServerLevel, MutableSet<Long>>()
        // Current collision sweeps must not queue behind speculative path extensions.
        // Admission and the total/per-tick ticket budgets remain unchanged.
        projectileResidency.expire(clock)
        val reservedKeys = projectileResidency.keys()
        guidedBombLeaseExpiry.entries.removeIf { it.value < clock || it.key !in reservedKeys }
        projectileResidency.removeIf { !hasProjectileCorridors(it.level) && it !in guidedBombLeaseExpiry }
        val projectileChunks = projectileResidency.keys()
        for (key in projectileChunks) {
            if (projectileResidency.required(key, clock))
                resident.getOrPut(key.level) { LongLinkedOpenHashSet() }.add(key.chunk)
        }
        for (state in sessions.values) {
            if (!FarRenderConfig.ENABLED.get()) continue
            val targets = resident.getOrPut(state.level) { LongLinkedOpenHashSet() }
            val index = FarVehicleIndex.get(state.level)
            for (id in state.selected) index.positions[id]?.let { position ->
                targets.add(FarTerrainPolicy.key(FarTerrainPolicy.chunk(position.x), FarTerrainPolicy.chunk(position.z)))
            }
        }
        for (key in projectileChunks) {
            resident.getOrPut(key.level) { LongLinkedOpenHashSet() }.add(key.chunk)
        }
        for ((level, old) in leased) {
            val keep = resident[level] ?: emptySet()
            old.filter { it !in keep }.forEach {
                level.chunkSource.removeRegionTicket(ticket, ChunkPos(it), 0, it)
                old.remove(it)
            }
        }
        var newTickets = FarTerrainDelivery.NEW_TICKETS_PER_TICK
        for ((level, chunks) in resident) for (key in chunks) {
            // Distance zero is FULL/TRACKED, not block/entity ticking. Normal simulation owns movement.
            val active = leased.getOrPut(level) { LongLinkedOpenHashSet() }
            if (key !in active) {
                if (newTickets <= 0) continue
                level.chunkSource.addRegionTicket(ticket, ChunkPos(key), 0, key)
                active.add(key)
                newTickets--
            } else if (clock % 20L == 0L) level.chunkSource.addRegionTicket(ticket, ChunkPos(key), 0, key)
        }
    }

    private fun discoveryChunks(center: Long, radius: Int): Sequence<Long> = sequence {
        val cx = FarTerrainPolicy.x(center); val cz = FarTerrainPolicy.z(center)
        yield(center)
        for (r in 1..radius) {
            for (dx in -r..r) for (dz in listOf(-r, r)) {
                if (dx * dx + dz * dz <= radius * radius) yield(FarTerrainPolicy.key(cx + dx, cz + dz))
            }
            for (dz in -r + 1 until r) for (dx in listOf(-r, r)) {
                if (dx * dx + dz * dz <= radius * radius) yield(FarTerrainPolicy.key(cx + dx, cz + dz))
            }
        }
    }

    fun clear() {
        for ((level, chunks) in leased) for (key in chunks) level.chunkSource.removeRegionTicket(ticket, ChunkPos(key), 0, key)
        leased.clear(); sessions.clear(); discoveries.clear(); projectileResidency.clear(); projectileReadiness.clear()
        nativeProjectileResidency.clear(); guidedBombLeaseExpiry.clear(); clock = 0
    }
}
