package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Positions survive ordinary chunk unloading; no entity instances or inventories are retained. */
class FarVehicleIndex : SavedData() {
    data class Position(val x: Double, val z: Double)
    val positions = LinkedHashMap<UUID, Position>()

    fun put(id: UUID, x: Double, z: Double) {
        if (!x.isFinite() || !z.isFinite() || kotlin.math.abs(x) > 30_000_000 || kotlin.math.abs(z) > 30_000_000) return
        if (id !in positions && positions.size >= 65536) return
        val next = Position(x, z)
        if (positions.put(id, next) != next) setDirty()
    }

    fun remove(id: UUID) { if (positions.remove(id) != null) setDirty() }

    /** Old worlds need no migration launch; discovered disk records never overwrite a newer observation. */
    fun discover(list: ListTag, depth: Int = 0) {
        if (depth > 16) return
        for (i in 0 until minOf(list.size, 1024)) {
            val tag = list.getCompound(i)
            if (tag.hasUUID("UUID") && tag.contains("TurretHealth") && tag.contains("LeftWheelHealth")) {
                val p = tag.getList("Pos", 6)
                val id = tag.getUUID("UUID")
                if (p.size == 3 && id !in positions) put(id, p.getDouble(0), p.getDouble(2))
            }
            discover(tag.getList("Passengers", 10), depth + 1)
        }
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        positions.forEach { (id, p) -> list.add(CompoundTag().also {
            it.putUUID("UUID", id); it.putDouble("X", p.x); it.putDouble("Z", p.z)
        }) }
        tag.put("Vehicles", list)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): FarVehicleIndex = FarVehicleIndex().also { index ->
                val list = tag.getList("Vehicles", 10)
                for (i in 0 until minOf(list.size, 65536)) {
                    val entry = list.getCompound(i)
                    if (entry.hasUUID("UUID")) index.put(entry.getUUID("UUID"), entry.getDouble("X"), entry.getDouble("Z"))
                }
        }
        fun get(level: ServerLevel): FarVehicleIndex = level.dataStorage.computeIfAbsent(::load,
            { FarVehicleIndex() }, "sbw_far_vehicle_index")
    }
}
