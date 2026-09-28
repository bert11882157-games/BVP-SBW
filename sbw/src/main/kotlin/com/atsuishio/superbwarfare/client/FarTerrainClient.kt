package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainFrameGate
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainVisibility
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleLighting
import com.atsuishio.superbwarfare.client.renderer.FarTerrainMeshes
import com.atsuishio.superbwarfare.compat.voxy.FarTerrainVoxy
import com.atsuishio.superbwarfare.config.client.FarVehicleRenderConfig
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.network.NetworkRegistry
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainChunk
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainPlan
import com.atsuishio.superbwarfare.network.message.send.FarTerrainAck
import com.atsuishio.superbwarfare.network.message.send.FarTerrainRequest
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import java.util.UUID
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Far-vehicle session and lighting. Current plans accept no custom terrain; cached terrain APIs
 * remain for codec/renderer compatibility and never grant client-side simulation. */
object FarTerrainClient : BlockAndTintGetter {
    data class Terrain(val chunk: LevelChunk, val sky: Map<Int, DataLayer>, val block: Map<Int, DataLayer>)
    private val terrain: MutableMap<Long, Terrain> = Long2ObjectLinkedOpenHashMap()
    private var desired: Set<Long> = emptySet()
    private var token = UUID.randomUUID().toString()
    private val gate = FarTerrainFrameGate()
    private val revision get() = gate.revision
    private val acknowledged get() = gate.acknowledged
    private var ticks = 0L
    private var enabled = false
    private var view = 0
    private var radius = 0
    private var lastAcked: Set<Long> = emptySet()
    private val pendingUpdates = LongOpenHashSet()
    private var visibleVehicles: List<String> = emptyList()
    private val coverageCache = com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainCoverageCache()

    fun prioritize(vehicles: List<VehicleEntity>) {
        visibleVehicles = vehicles.take(com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleStore.MAX_VEHICLES)
            .map { it.stringUUID }.distinct()
    }

    @JvmStatic fun clear() {
        coverageCache.clear()
        FarEffectsClient.clear()
        FarTerrainMeshes.clear()
        FarTerrainVoxy.clear()
        terrain.clear(); desired = emptySet(); token = UUID.randomUUID().toString()
        gate.clear()
        ticks = 0; radius = 0; view = 0; enabled = false
        lastAcked = emptySet()
        pendingUpdates.clear()
        visibleVehicles = emptyList()
    }

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        ticks++
        val nextView = mc.options.renderDistance().get().coerceIn(2, 32)
        val nextEnabled = FarVehicleRenderConfig.ENABLED.get()
        if (view != nextView || enabled != nextEnabled || ticks % 5L == 1L) {
            if (view != nextView || enabled != nextEnabled) {
                clear()
                FarVehicleClient.store.clear()
            }
            view = nextView; enabled = nextEnabled
            val camera = mc.gameRenderer.mainCamera.position
            val dx = camera.x - (mc.player?.x ?: camera.x)
            val dz = camera.z - (mc.player?.z ?: camera.z)
            val scale = minOf(1.0, 128.0 / kotlin.math.hypot(dx, dz).coerceAtLeast(0.001))
            NetworkRegistry.PACKET_HANDLER.sendToServer(FarTerrainRequest(token,
                level.dimension().location().toString(), view, enabled, visibleVehicles, dx * scale, dz * scale))
        }
        if (!enabled) {
            FarTerrainMeshes.clear(); terrain.clear(); desired = emptySet(); radius = 0
            return
        }
        // An empty subscription plan authenticates frames; terrain does not gate drawing.
        if (ticks % 5L != 0L) return
        val complete = terrain.keys.filterTo(LongOpenHashSet()) { it in desired && it !in pendingUpdates }
        if (ticks % 20L == 0L && EliteDiagnostics.isClientEnabled()) EliteDiagnostics.recordClient(level.gameTime, "far_terrain", "CLIENT_STATE",
            "token", token, "revision", revision, "acknowledged", acknowledged, "committed", gate.committed,
            "radius", radius, "desired_chunks", desired.size, "received_chunks", terrain.size,
            "refresh_pending_chunks", pendingUpdates.size,
            "mesh_ready_chunks", complete.count { FarTerrainMeshes.ready(it) },
            "mesh_pending_sections", FarTerrainMeshes.pendingSections(),
            "mesh_bytes", FarTerrainMeshes.allocatedBytes(),
            "mesh_budget_blocked", FarTerrainMeshes.budgetBlockedSections(),
            "mesh_failed_sections", FarTerrainMeshes.failedSections(),
            "plan_age", gate.age(ticks), "ready", ready(),
            "voxy", FarTerrainVoxy.status())
        if (revision >= 0 && (acknowledged != revision || complete != lastAcked) && ticks % 5L == 0L) {
            gate.acknowledge()
            lastAcked = complete
            NetworkRegistry.PACKET_HANDLER.sendToServer(FarTerrainAck(token,
                level.dimension().location().toString(), revision, complete.toList()))
        }
    }

    fun plan(message: FarTerrainPlan) {
        val level = Minecraft.getInstance().level ?: return
        val requested = LongLinkedOpenHashSet(message.chunks)
        if (!enabled || message.token != token || message.dimension != level.dimension().location().toString() ||
            message.revision < revision ||
            message.radius !in FarTerrainPolicy.MIN_ACQUISITION_RADIUS..FarTerrainPolicy.MAX_ACQUISITION_RADIUS ||
            message.chunks.isNotEmpty() || message.invalidated.isNotEmpty() ||
            requested.size != message.chunks.size || !requested.containsAll(message.invalidated)) return
        if (!gate.plan(message.revision, ticks)) return
        radius = message.radius
        EliteDiagnostics.recordClient(level.gameTime, "far_terrain", "PLAN_RECEIVED", "token", token,
            "revision", revision, "radius", radius, "chunks", message.chunks.size, "invalidated", message.invalidated.size)
        desired = requested
        val removed = terrain.keys.filter { it !in desired }
        removed.forEach { terrain.remove(it); FarTerrainMeshes.invalidate(it) }
        // Keep the last received terrain and uploaded mesh until its replacement arrives.
        // A refresh is not an unload. As with vanilla chunk rebuilding, swap completed meshes.
        pendingUpdates.retainAll(desired)
        pendingUpdates.addAll(message.invalidated)
    }

    fun receive(message: FarTerrainChunk) {
        val level = Minecraft.getInstance().level ?: return
        if (!enabled || message.token != token || message.dimension != level.dimension().location().toString() ||
            message.revision != revision || message.chunk !in desired || message.data.size > FarTerrainPolicy.MAX_CHUNK_BYTES) return
        val input = FriendlyByteBuf(Unpooled.wrappedBuffer(message.data))
        try {
            val packet = ClientboundLevelChunkWithLightPacket(input)
            require(input.readableBytes() == 0 && ChunkPos.asLong(packet.x, packet.z) == message.chunk) { "Mismatched far terrain chunk" }
            val chunk = LevelChunk(level, ChunkPos(packet.x, packet.z))
            val chunkInput = packet.chunkData.readBuffer
            try { chunk.replaceWithPacketData(chunkInput, packet.chunkData.heightmaps,
                packet.chunkData.getBlockEntitiesTagsConsumer(packet.x, packet.z)) }
            finally { chunkInput.release() }
            fun lights(mask: java.util.BitSet, empty: java.util.BitSet, updates: List<ByteArray>): Map<Int, DataLayer> {
                val result = HashMap<Int, DataLayer>()
                var index = 0
                val min = level.minSection - 1
                require(mask.length() <= level.sectionsCount + 2 && empty.length() <= level.sectionsCount + 2)
                for (bit in 0 until level.sectionsCount + 2) {
                    if (mask[bit]) {
                        require(index < updates.size && updates[index].size == 2048)
                        result[min + bit] = DataLayer(updates[index++])
                    } else if (empty[bit]) result[min + bit] = DataLayer()
                }
                require(index == updates.size)
                return result
            }
            val light = packet.lightData
            val record = Terrain(chunk, lights(light.skyYMask, light.emptySkyYMask, light.skyUpdates),
                lights(light.blockYMask, light.emptyBlockYMask, light.blockUpdates))
            val previous = terrain.put(message.chunk, record)
            pendingUpdates.remove(message.chunk)
            if (previous != null) {
                val lightChanged = !sameLights(previous.sky, record.sky) || !sameLights(previous.block, record.block)
                for (index in chunk.sections.indices) {
                    if (!lightChanged && sameSection(previous.chunk.sections[index], chunk.sections[index])) continue
                    val y = chunk.minSection + index
                    // Adjacent faces and ambient occlusion can depend on this section's cells.
                    for (dx in -1..1) for (dz in -1..1) for (dy in -1..1) {
                        FarTerrainMeshes.refresh(FarTerrainPolicy.key(packet.x + dx, packet.z + dz), y + dy)
                    }
                }
            }
            FarTerrainVoxy.ingest(record)
        } finally { input.release() }
    }

    private fun sameLights(a: Map<Int, DataLayer>, b: Map<Int, DataLayer>): Boolean =
        a.keys == b.keys && a.all { (y, layer) -> layer.data.contentEquals(b.getValue(y).data) }

    private fun sameSection(a: net.minecraft.world.level.chunk.LevelChunkSection,
                            b: net.minecraft.world.level.chunk.LevelChunkSection): Boolean {
        val first = FriendlyByteBuf(Unpooled.buffer())
        val second = FriendlyByteBuf(Unpooled.buffer())
        return try {
            a.write(first); b.write(second)
            io.netty.buffer.ByteBufUtil.equals(first, second)
        } finally { first.release(); second.release() }
    }

    fun acceptFrame(frameToken: String, frameRevision: Long): Boolean {
        return enabled && frameToken == token && gate.acceptFrame(frameRevision, ticks)
    }

    fun frameCommitted(frameRevision: Long) { gate.commit(frameRevision) }
    @JvmStatic fun ready(): Boolean = enabled && gate.ready(ticks)
    @JvmStatic fun radius(): Int = if (enabled) radius else 0
    /** Projection follows retained vehicles; acquisition radius is not a visibility cutoff. */
    // renderRadius is asked per distant particle per frame; its inputs (camera, far vehicle entries, radius) rarely
    // change within a frame, so the last answer is reused while they are the same.
    private var radiusMemo = Int.MIN_VALUE
    private var radiusMemoX = Double.NaN
    private var radiusMemoY = Double.NaN
    private var radiusMemoZ = Double.NaN
    private var radiusMemoRevision = -1L
    private var radiusMemoBase = Int.MIN_VALUE
    private var radiusMemoStore: Any? = null

    @JvmStatic fun renderRadius(): Int {
        if (!enabled) return 0
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        val store = FarVehicleClient.store
        if (radiusMemo != Int.MIN_VALUE && camera.x == radiusMemoX && camera.y == radiusMemoY && camera.z == radiusMemoZ &&
            store === radiusMemoStore && store.revision == radiusMemoRevision && radius == radiusMemoBase) return radiusMemo
        val farthest = store.values().maxOfOrNull {
            kotlin.math.sqrt(it.current.distanceSquared(camera.x, camera.y, camera.z)) + 64.0
        } ?: 0.0
        val result = maxOf(radius, com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.PROJECTILE_VISIBILITY_RADIUS,
            kotlin.math.ceil(farthest).toInt())
        radiusMemo = result; radiusMemoX = camera.x; radiusMemoY = camera.y; radiusMemoZ = camera.z
        radiusMemoRevision = store.revision; radiusMemoBase = radius; radiusMemoStore = store
        return result
    }
    @JvmStatic fun chunks(): Collection<Terrain> = terrain.values
    @JvmStatic fun contains(key: Long): Boolean = key in terrain
    @JvmStatic fun allReceived(): Boolean = terrain.keys.containsAll(desired)
    fun sections(camera: net.minecraft.world.phys.Vec3, box: net.minecraft.world.phys.AABB) =
        coverageCache.get(camera, box, minSection, maxSection).sections

    fun covers(px: Double, pz: Double, box: net.minecraft.world.phys.AABB): Boolean =
        covers(net.minecraft.world.phys.Vec3(px, Minecraft.getInstance().gameRenderer.mainCamera.position.y, pz), box)

    @JvmOverloads
    fun covers(camera: net.minecraft.world.phys.Vec3, box: net.minecraft.world.phys.AABB,
               prepared: Set<FarTerrainVisibility.Section>? = null): Boolean {
        if (!ready()) return false
        val coverage = coverageCache.get(camera, box, minSection, maxSection)
        return coverage.chunks.size <= FarTerrainPolicy.MAX_CHUNKS &&
            coverage.chunks.all { it in desired && it in terrain } &&
            (prepared ?: coverage.sections).all { FarTerrainMeshes.ready(it) }
    }
    @JvmStatic fun terrain(key: Long): Terrain? = terrain[key]

    /** Route distant native entities through the same far pass as their retained copies. */
    @JvmStatic fun deferNative(vehicle: VehicleEntity): Boolean {
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera.position
        val dx = vehicle.x - camera.x; val dz = vehicle.z - camera.z
        val box = vehicle.boundingBox
        return FarTerrainPolicy.deferNative(dx * dx + dz * dz, mc.options.effectiveRenderDistance,
            maxOf(box.xsize, box.zsize) / 2.0, FarVehicleRenderConfig.ENABLED.get(),
            mc.player?.let { vehicle.hasPassenger(it) } == true)
    }

    override fun getHeight(): Int = Minecraft.getInstance().level?.height ?: 384
    override fun getMinBuildHeight(): Int = Minecraft.getInstance().level?.minBuildHeight ?: -64
    override fun getBlockEntity(pos: BlockPos): BlockEntity? {
        val record = terrain[ChunkPos.asLong(pos)]
        return if (record != null) record.chunk.blockEntities[pos] else Minecraft.getInstance().level?.getBlockEntity(pos)
    }
    override fun getBlockState(pos: BlockPos): BlockState = terrain[ChunkPos.asLong(pos)]?.chunk?.getBlockState(pos)
        ?: Minecraft.getInstance().level?.getBlockState(pos) ?: Blocks.AIR.defaultBlockState()
    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
    override fun getShade(direction: Direction, shade: Boolean): Float = Minecraft.getInstance().level!!.getShade(direction, shade)
    override fun getLightEngine(): LevelLightEngine = Minecraft.getInstance().level!!.lightEngine
    /** Missing client chunks have no usable light data. Preserve native lighting where available. */
    @JvmStatic fun lightOverride(layer: LightLayer, pos: BlockPos): Int? {
        val level = Minecraft.getInstance().level ?: return null
        if (!ready() || level.hasChunk(pos.x shr 4, pos.z shr 4)) return null
        return FarVehicleLighting.unloaded(layer == LightLayer.SKY, level.dimensionType().hasSkyLight(),
            pos.y, level.minBuildHeight)
    }

    override fun getBrightness(layer: LightLayer, pos: BlockPos): Int {
        val record = terrain[ChunkPos.asLong(pos)] ?: return lightOverride(layer, pos)
            ?: Minecraft.getInstance().level!!.getBrightness(layer, pos)
        val section = pos.y shr 4
        if (layer == LightLayer.BLOCK) return record.block[section]?.get(pos.x and 15, pos.y and 15, pos.z and 15) ?: 0
        return com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainLight.sky(
            Minecraft.getInstance().level?.dimensionType()?.hasSkyLight() == true, record.sky, pos)
    }
    override fun getRawBrightness(pos: BlockPos, darken: Int): Int =
        maxOf(getBrightness(LightLayer.BLOCK, pos), getBrightness(LightLayer.SKY, pos) - darken)
    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        val chunk = terrain[ChunkPos.asLong(pos)]?.chunk ?: return Minecraft.getInstance().level!!.getBlockTint(pos, resolver)
        return resolver.getColor(chunk.getNoiseBiome(pos.x shr 2, pos.y shr 2, pos.z shr 2).value(), pos.x.toDouble(), pos.z.toDouble())
    }
}
