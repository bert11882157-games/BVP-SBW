package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Aircraft-owned destinations survive unloading, seat changes and a server restart. */
class AircraftDesignationData : SavedData() {
    data class Point(val revision: Long, val position: Vec3?)
    private val points = mutableMapOf<UUID, Point>()
    fun get(aircraft: UUID) = points[aircraft]
    fun put(aircraft: UUID, position: Vec3?): Boolean {
        if (!points.containsKey(aircraft) && points.size >= 4096) return false
        points[aircraft] = Point((points[aircraft]?.revision ?: 0) + 1, position)
        setDirty(); return true
    }
    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, point) in points) list.add(CompoundTag().also {
            it.putUUID("Aircraft", id); it.putLong("Revision", point.revision)
            point.position?.let { p -> it.putDouble("X", p.x); it.putDouble("Y", p.y); it.putDouble("Z", p.z) }
        })
        tag.put("Points", list); return tag
    }
    companion object {
        fun get(level: ServerLevel): AircraftDesignationData = level.dataStorage.computeIfAbsent({ load(it) },
            { AircraftDesignationData() }, "bvp_aircraft_designations")
        internal fun load(tag: CompoundTag): AircraftDesignationData = AircraftDesignationData().also { data ->
            for (entry in tag.getList("Points", 10).take(4096)) {
                val n = entry as CompoundTag
                if (!n.hasUUID("Aircraft")) continue
                val p = if (n.contains("X")) Vec3(n.getDouble("X"), n.getDouble("Y"), n.getDouble("Z")) else null
                if (p != null && (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite())) continue
                data.points[n.getUUID("Aircraft")] = Point(n.getLong("Revision"), p)
            }
        }
    }
}
