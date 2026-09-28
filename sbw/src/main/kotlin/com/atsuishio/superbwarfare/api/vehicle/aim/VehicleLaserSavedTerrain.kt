package com.atsuishio.superbwarfare.api.vehicle.aim

import com.mojang.serialization.Codec
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.storage.ChunkStorage
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

/** Reads saved block palettes only. No chunk deserialization, generation, entities, or tickets. */
internal object VehicleLaserSavedTerrain {
    private val reads = Semaphore(8)
    private val blockCodec: Codec<PalettedContainer<BlockState>> = PalettedContainer.codecRW(
        Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
        Blocks.AIR.defaultBlockState())

    /** Reads kept in flight ahead of the chunk being examined, so a long ray is not one disk round trip per chunk. */
    private const val READ_AHEAD = 12

    fun trace(level: ServerLevel, start: Vec3, end: Vec3,
              missing: LinkedHashMap<ChunkPos, MutableList<BlockPos>>, known: Vec3?): CompletableFuture<Double?> {
        val chunks = missing.entries.toList()
        // An 8192-block diagonal ray crosses fewer than 730 horizontal chunks.
        if (chunks.size > 1024) return CompletableFuture.completedFuture(null)
        if (!reads.tryAcquire()) return CompletableFuture.completedFuture(null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val chunkMap = level.chunkSource.chunkMap
        val dimension = level.dimension()
        val storage = java.util.function.Supplier { level.dataStorage }
        val current = SharedConstants.getCurrentVersion().dataVersion.version
        val pending = arrayOfNulls<CompletableFuture<BlockGetter?>>(chunks.size)
        fun load(index: Int): CompletableFuture<BlockGetter?> = pending[index] ?: run {
            val chunkPos = chunks[index].key
            chunkMap.read(chunkPos).thenApplyAsync { saved ->
                val raw = saved.orElse(null) ?: return@thenApplyAsync null
                // Chunks saved by an older game version are upgraded exactly as a chunk load would upgrade them.
                val tag = if (ChunkStorage.getVersion(raw) < current)
                    chunkMap.upgradeChunkTag(dimension, storage, raw, java.util.Optional.empty()) else raw
                decode(tag, chunkPos, level.minBuildHeight, level.height)
            }.exceptionally { null }.also { pending[index] = it }
        }
        fun next(index: Int): CompletableFuture<Double?> {
            if (index == chunks.size) return CompletableFuture.completedFuture(known?.let(start::distanceTo))
            if (System.nanoTime() >= deadline) return CompletableFuture.completedFuture(null)
            for (ahead in index until minOf(chunks.size, index + READ_AHEAD)) load(ahead)
            return load(index).thenComposeAsync { view ->
                pending[index] = null
                // A chunk that was never saved, or cannot be read, is skipped rather than failing the whole
                // ray: the far terrain the player can see (Voxy) is in chunks that were saved.
                val point = view?.let { firstHit(it, chunks[index].value, start, end) }
                if (point != null) CompletableFuture.completedFuture<Double?>(start.distanceTo(point))
                else next(index + 1)
            }
        }
        return try {
            next(0).orTimeout(8, TimeUnit.SECONDS).exceptionally { null }.whenComplete { _, _ -> reads.release() }
        } catch (_: RuntimeException) {
            reads.release()
            CompletableFuture.completedFuture(null)
        }
    }

    internal fun firstHit(view: BlockGetter, cells: List<BlockPos>, start: Vec3, end: Vec3): Vec3? {
        for (pos in cells) {
            val state = view.getBlockState(pos)
            val hit = state.getCollisionShape(view, pos).clip(start, end, pos)
            if (hit != null) return hit.location
        }
        return null
    }

    /**
     * Statuses whose block palette already holds the finished terrain shape: from "surface" on, only carvers and
     * decoration (trees, ores) are still to come. Chunks at the edge of explored land are often saved short of
     * "full", and failing on them left long rays with no return.
     */
    private val TERRAIN_STATUSES = setOf("surface", "carvers", "liquid_carvers", "features", "light", "spawn",
        "heightmaps", "full")

    internal fun terrainFinal(status: String): Boolean = status.removePrefix("minecraft:") in TERRAIN_STATUSES

    internal fun decode(tag: CompoundTag, expected: ChunkPos, minimumY: Int, worldHeight: Int): BlockGetter? {
        // A chunk that is not readable terrain returns null; the trace skips it and keeps looking beyond.
        if (!tag.contains("sections", Tag.TAG_LIST.toInt()) || tag.getInt("xPos") != expected.x ||
            tag.getInt("zPos") != expected.z || !terrainFinal(tag.getString("Status"))) return null
        val sections = HashMap<Int, PalettedContainer<BlockState>>()
        val list = tag.getList("sections", Tag.TAG_COMPOUND.toInt())
        for (index in 0 until list.size) {
            val section = list.getCompound(index)
            if (!section.contains("block_states", Tag.TAG_COMPOUND.toInt())) continue
            val palette = blockCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result().orElse(null)
                ?: return null
            sections[section.getByte("Y").toInt()] = palette
        }
        return object : BlockGetter {
            override fun getHeight() = worldHeight
            override fun getMinBuildHeight() = minimumY
            override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
            override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
            override fun getBlockState(pos: BlockPos): BlockState =
                if (pos.x shr 4 != expected.x || pos.z shr 4 != expected.z) Blocks.AIR.defaultBlockState()
                else sections[pos.y shr 4]?.get(pos.x and 15, pos.y and 15, pos.z and 15)
                    ?: Blocks.AIR.defaultBlockState()
        }
    }
}
