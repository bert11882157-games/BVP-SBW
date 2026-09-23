package com.atsuishio.superbwarfare.compat.voxy

import com.atsuishio.superbwarfare.client.FarTerrainClient
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional read-only Voxy cache for cosmetic debris. Disk reads never run on the render/game thread. */
object ClientDebrisTerrain : BlockGetter {
    private data class Key(val x: Int, val y: Int, val z: Int)
    private data class Cached(val states: Array<BlockState>?, val tick: Long)
    private data class Api(val engine: Method, val retain: Method, val releaseEngine: Method,
                           val acquire: Method, val releaseSection: Method, val copy: Method,
                           val index: Method, val mapper: Method, val blockId: Method, val blockState: Method)
    private val reader = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "Aircraft debris terrain").apply { isDaemon = true } })
    private val cache = LinkedHashMap<Key, Cached>()
    private val pending = LinkedHashMap<Key, Future<Array<BlockState>?>>()
    private var api: Api? = null
    private var attempted = false
    private var world: Any? = null
    private var tick = 0L
    private var indices: IntArray? = null

    fun clear() {
        // Queued jobs own an engine reference: let their finally blocks release it.
        pending.clear(); cache.clear(); tick = 0
        world = Minecraft.getInstance().level
        api = null; attempted = false; indices = null
    }
    fun tick() {
        if (world !== Minecraft.getInstance().level) clear()
        tick++
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isDone) continue
            cache[entry.key] = Cached(runCatching { entry.value.get() }.getOrNull(), tick)
            iterator.remove()
            if (cache.size > 32) cache.remove(cache.keys.first())
        }
        cache.entries.removeIf { tick - it.value.tick > if (it.value.states == null) 40L else 200L }
    }
    fun prefetch(position: Vec3) { request(BlockPos.containing(position)) }

    private fun resolve(): Api? {
        if (attempted) return api
        attempted = true
        api = runCatching {
            val identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
            val engine = Class.forName("me.cortex.voxy.common.world.WorldEngine")
            val section = Class.forName("me.cortex.voxy.common.world.WorldSection")
            val mapper = Class.forName("me.cortex.voxy.common.world.other.Mapper")
            val ints = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            Api(identifier.methods.first { it.name == "ofEngineNullable" && it.parameterCount == 1 },
                engine.getMethod("acquireRef"), engine.getMethod("releaseRef"),
                engine.getMethod("acquireIfExists", Int::class.javaPrimitiveType, *ints),
                section.getMethod("release"), section.getMethod("copyData"), section.getMethod("getIndex", *ints),
                engine.getMethod("getMapper"), mapper.getMethod("getBlockId", Long::class.javaPrimitiveType),
                mapper.getMethod("getBlockStateFromBlockId", Int::class.javaPrimitiveType))
        }.mapCatching { resolved ->
            // Public indexing API; a failed optional integration must never interrupt rendering.
            indices = IntArray(32768) { linear ->
                resolved.index.invoke(null, linear and 31, (linear ushr 5) and 31, linear ushr 10) as Int
            }
            resolved
        }.getOrNull()
        return api
    }
    private fun request(pos: BlockPos) {
        val level = Minecraft.getInstance().level ?: return
        if (level.hasChunk(pos.x shr 4, pos.z shr 4) || FarTerrainClient.contains(net.minecraft.world.level.ChunkPos.asLong(pos))) return
        val key = Key(pos.x shr 5, pos.y shr 5, pos.z shr 5)
        if (key in cache || key in pending || pending.size >= 4) return
        val bridge = resolve() ?: return
        val engine = runCatching { bridge.engine.invoke(null, level) }.getOrNull() ?: return
        if (runCatching { bridge.retain.invoke(engine) }.isFailure) return
        try {
            pending[key] = reader.submit<Array<BlockState>?> {
                try {
                    val section = bridge.acquire.invoke(engine, 0, key.x, key.y, key.z) ?: return@submit null
                    try {
                        val values = bridge.copy.invoke(section) as LongArray
                        if (values.size != 32768) return@submit null
                        val mapper = bridge.mapper.invoke(engine)
                        val states = HashMap<Int, BlockState>()
                        Array(values.size) { index ->
                            val id = bridge.blockId.invoke(null, values[index]) as Int
                            states.getOrPut(id) { bridge.blockState.invoke(mapper, id) as? BlockState ?: Blocks.AIR.defaultBlockState() }
                        }
                    } finally { bridge.releaseSection.invoke(section) }
                } finally { bridge.releaseEngine.invoke(engine) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { bridge.releaseEngine.invoke(engine) }
    }
    override fun getBlockState(pos: BlockPos): BlockState {
        val level = Minecraft.getInstance().level ?: return Blocks.AIR.defaultBlockState()
        if (level.hasChunk(pos.x shr 4, pos.z shr 4) || FarTerrainClient.contains(net.minecraft.world.level.ChunkPos.asLong(pos)))
            return FarTerrainClient.getBlockState(pos)
        val key = Key(pos.x shr 5, pos.y shr 5, pos.z shr 5)
        val data = cache[key]?.states
        if (data == null) { request(pos); return Blocks.AIR.defaultBlockState() }
        val index = (pos.x and 31) or ((pos.y and 31) shl 5) or ((pos.z and 31) shl 10)
        return data[indices!![index]]
    }
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
    override fun getFluidState(pos: BlockPos) = getBlockState(pos).fluidState
    override fun getHeight(): Int = FarTerrainClient.height
    override fun getMinBuildHeight(): Int = FarTerrainClient.minBuildHeight
}
