package com.atsuishio.superbwarfare.api.vehicle.aim

import com.mojang.serialization.Codec
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

    fun trace(level: ServerLevel, start: Vec3, end: Vec3,
              missing: LinkedHashMap<ChunkPos, MutableList<BlockPos>>, known: Vec3?): CompletableFuture<Double?> {
        val chunks = missing.entries.toList()
        // An 8192-block diagonal ray crosses fewer than 730 horizontal chunks.
        if (chunks.size > 1024) return CompletableFuture.completedFuture(null)
        if (!reads.tryAcquire()) return CompletableFuture.completedFuture(null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        fun next(index: Int): CompletableFuture<Double?> {
            if (index == chunks.size) return CompletableFuture.completedFuture(known?.let(start::distanceTo))
            if (System.nanoTime() >= deadline) return CompletableFuture.completedFuture(null)
            val (chunkPos, cells) = chunks[index]
            return level.chunkSource.chunkMap.read(chunkPos).thenComposeAsync { saved ->
                val tag = saved.orElse(null)
                val view = tag?.let { decode(it, chunkPos, level.minBuildHeight, level.height) }
                    ?: return@thenComposeAsync CompletableFuture.completedFuture<Double?>(null)
                val point = firstHit(view, cells, start, end)
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

    internal fun decode(tag: CompoundTag, expected: ChunkPos, minimumY: Int, worldHeight: Int): BlockGetter? {
        // Unknown/old schemas are a no-return, never interpreted as clear terrain.
        if (!tag.contains("sections", Tag.TAG_LIST.toInt()) || tag.getInt("xPos") != expected.x ||
            tag.getInt("zPos") != expected.z || tag.getString("Status") !in setOf("minecraft:full", "full")) return null
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
