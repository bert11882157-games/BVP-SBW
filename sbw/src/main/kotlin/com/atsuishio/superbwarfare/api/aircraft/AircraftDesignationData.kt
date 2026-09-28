package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import java.util.UUID

/** Aircraft-owned destinations survive unloading, seat changes and a server restart. */
class AircraftDesignationData : SavedData() {
    /**
     * A designated point. When the laser landed on a vehicle, [target] is that vehicle and [offset] the spot in its
     * own (hull) frame, so the designation stays on the vehicle while it moves; [position] is then its last known
     * world position, used while the vehicle is unloaded or gone.
     */
    data class Point(val revision: Long, val position: Vec3?, val target: UUID? = null, val offset: Vec3? = null) {
        /** The spot now: on the tracked vehicle when it is loaded and alive, else the stored position. */
        fun live(level: ServerLevel): Vec3? {
            val vehicle = target?.let { level.getEntity(it) } as? VehicleEntity
            if (vehicle == null || offset == null || vehicle.isRemoved || !vehicle.isAlive) return position
            return worldPoint(vehicle, offset) ?: position
        }
    }
    private val points = mutableMapOf<UUID, Point>()
    fun get(aircraft: UUID) = points[aircraft]
    fun put(aircraft: UUID, position: Vec3?, target: VehicleEntity? = null): Boolean {
        if (!points.containsKey(aircraft) && points.size >= 4096) return false
        val offset = if (target != null && position != null) localPoint(target, position) else null
        points[aircraft] = Point((points[aircraft]?.revision ?: 0) + 1, position,
            target?.uuid?.takeIf { offset != null }, offset)
        setDirty(); return true
    }
    /** Moves a tracked designation's stored position to where its vehicle is now, keeping the track. */
    fun follow(aircraft: UUID, position: Vec3): Point? {
        val old = points[aircraft] ?: return null
        if (old.target == null) return null
        return old.copy(revision = old.revision + 1, position = position).also { points[aircraft] = it; setDirty() }
    }
    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, point) in points) list.add(CompoundTag().also {
            it.putUUID("Aircraft", id); it.putLong("Revision", point.revision)
            point.position?.let { p -> it.putDouble("X", p.x); it.putDouble("Y", p.y); it.putDouble("Z", p.z) }
            if (point.target != null && point.offset != null) {
                it.putUUID("Target", point.target)
                it.putDouble("OX", point.offset.x); it.putDouble("OY", point.offset.y); it.putDouble("OZ", point.offset.z)
            }
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
                val offset = if (n.hasUUID("Target")) Vec3(n.getDouble("OX"), n.getDouble("OY"), n.getDouble("OZ"))
                    .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() } else null
                data.points[n.getUUID("Aircraft")] = Point(n.getLong("Revision"), p,
                    if (offset != null) n.getUUID("Target") else null, offset)
            }
        }

        internal fun localPoint(vehicle: VehicleEntity, world: Vec3): Vec3? {
            val local = Matrix4d(vehicle.getVehicleTransform(1f)).invert().transformPosition(Vector3d(world.x, world.y, world.z))
            return Vec3(local.x, local.y, local.z).takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        }

        internal fun worldPoint(vehicle: VehicleEntity, local: Vec3): Vec3? {
            val w = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
            return Vec3(w.x, w.y, w.z).takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        }
    }
}
