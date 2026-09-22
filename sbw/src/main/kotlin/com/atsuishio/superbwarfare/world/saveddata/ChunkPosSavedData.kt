package com.atsuishio.superbwarfare.world.saveddata

import com.atsuishio.superbwarfare.config.server.VehicleConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSimulationPolicy
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.TicketType
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.saveddata.SavedData
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

class ChunkPosSavedData : SavedData() {
    val chunkPositions = mutableSetOf<ChunkPos>()

    override fun save(tag: CompoundTag): CompoundTag {
        tag.put("Pos", this.savePos())
        return tag
    }

    fun savePos(): ListTag {
        val tag = ListTag()
        for (pos in chunkPositions) {
            tag.add(CompoundTag().also {
                it.putInt("X", pos.x)
                it.putInt("Z", pos.z)
            })
        }
        return tag
    }

    fun loadPos(tag: ListTag) {
        val list = mutableListOf<ChunkPos>()
        for (t in tag.indices) {
            val pos = tag[t] as? CompoundTag ?: continue
            list.add(ChunkPos(pos.getInt("X"), pos.getInt("Z")))
        }
        this.chunkPositions.addAll(list)
    }

    fun clearPos() {
        this.chunkPositions.clear()
    }

    @Mod.EventBusSubscriber
    companion object {
        const val FILE_ID: String = "superbwarfare_chunk_pos"
        private val restoreTicket = TicketType.create<Long>("sbw_vehicle_restore",
            Comparator { a, b -> a.compareTo(b) }, 200)
        private data class Restore(val level: ServerLevel, val position: ChunkPos, val data: ChunkPosSavedData)
        private val pendingRestores = ArrayDeque<Restore>()

        fun load(tag: CompoundTag): ChunkPosSavedData {
            val savedData = ChunkPosSavedData()
            if (tag.contains("Pos", Tag.TAG_LIST.toInt())) {
                savedData.loadPos(tag.getList("Pos", Tag.TAG_COMPOUND.toInt()))
            }
            return savedData
        }

        @SubscribeEvent
        fun posSavedDataOnServerStarted(event: ServerStartedEvent) {
            val server = event.server
            pendingRestores.clear()
            if (!VehicleConfig.VEHICLE_CHUNK_LOADING.get()) return

            for (level in server.allLevels) {
                val data = level.dataStorage.get({ load(it) }, FILE_ID) ?: continue
                val posSet = data.chunkPositions
                if (posSet.isEmpty()) continue

                for (pos in posSet) {
                    pendingRestores.addLast(Restore(level, pos, data))
                }
            }
        }

        /** Bootstrap only: active vehicles renew their own tickets after loading; parked ones rest. */
        @SubscribeEvent
        fun restoreVehicleChunks(event: TickEvent.ServerTickEvent) {
            if (event.phase != TickEvent.Phase.END) return
            repeat(4) {
                val request = pendingRestores.removeFirstOrNull() ?: return
                val pos = request.position
                request.level.chunkSource.addRegionTicket(restoreTicket, pos, 3, pos.toLong())
                // Unissued requests remain saved if the server stops partway through the queue.
                request.data.chunkPositions.remove(pos)
                request.data.setDirty()
            }
        }

        @SubscribeEvent
        fun posSavedDataOnServerStopping(event: ServerStoppingEvent) {
            val server = event.server
            if (!VehicleConfig.VEHICLE_CHUNK_LOADING.get()) return

            for (level in server.allLevels) {
                val data = level.dataStorage.computeIfAbsent(
                    { load(it) },
                    { ChunkPosSavedData() },
                    FILE_ID
                ) ?: continue

                val list = level.allEntities
                    .asSequence()
                    .filter { it is VehicleEntity && it.computed().keepChunkLoaded &&
                        FarVehicleSimulationPolicy.shouldRenewSimulationTicket(it) }
                    .map { it.chunkPosition() }
                    .toList()
                if (list.isEmpty()) continue

                data.chunkPositions.addAll(list)
                data.setDirty()
            }
            pendingRestores.clear()
        }
    }
}
