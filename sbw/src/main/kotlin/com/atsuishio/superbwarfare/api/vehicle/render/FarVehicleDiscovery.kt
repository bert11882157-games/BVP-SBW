package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.mixins.FarTerrainEntityManagerAccessor
import com.atsuishio.superbwarfare.mixins.FarTerrainEntityStorageAccessor
import com.atsuishio.superbwarfare.mixins.FarTerrainServerLevelAccessor
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.nio.file.Files
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource

/** Reads saved entity metadata through vanilla's existing serialized IO queue, without generating chunks. */
internal class FarVehicleDiscovery(private val level: ServerLevel) {
    private val visited = LinkedHashMap<Long, Long>()
    private val pending = LinkedHashMap<Long, CompletableFuture<Optional<CompoundTag>>>()
    private val regions = HashMap<Long, Pair<Long, Boolean>>()
    private val directory by lazy {
        DimensionType.getStorageFolder(level.dimension(), level.server.getWorldPath(LevelResource.ROOT)).resolve("entities")
    }
    private val worker by lazy {
        val manager = (level as FarTerrainServerLevelAccessor).`sbw$getEntityManager`()
        val storage = (manager as FarTerrainEntityManagerAccessor).`sbw$getStorage`()
        (storage as FarTerrainEntityStorageAccessor).`sbw$getWorker`()
    }

    fun pump(candidates: Iterator<Long>, tick: Long) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (key, future) = iterator.next()
            if (!future.isDone) continue
            iterator.remove()
            if (!future.isCompletedExceptionally) {
                future.getNow(Optional.empty()).ifPresent { FarVehicleIndex.get(level).discover(it.getList("Entities", 10)) }
                visited[key] = tick
            } else visited[key] = tick - 500 // Retry after a bounded cooldown; never block the server thread.
        }
        var attempts = 0
        while (pending.size < FarTerrainPolicy.DISCOVERY_PER_TICK && candidates.hasNext() && attempts++ < 64) {
            val key = candidates.next()
            if (key in pending || tick - (visited[key] ?: -12000) < 600) continue
            if (level.areEntitiesLoaded(key)) { visited[key] = tick; continue }
            val pos = ChunkPos(key)
            val region = FarTerrainPolicy.key(pos.x shr 5, pos.z shr 5)
            val cached = regions[region]
            val exists = if (cached != null && tick - cached.first < 600) cached.second else
                Files.isRegularFile(directory.resolve("r.${pos.x shr 5}.${pos.z shr 5}.mca")).also { regions[region] = tick to it }
            if (!exists) { visited[key] = tick; continue }
            pending[key] = worker.loadAsync(ChunkPos(key))
        }
        while (visited.size > 32768) visited.remove(visited.keys.first())
        if (regions.size > 1024) regions.entries.removeIf { tick - it.value.first > 600 }
    }

}
