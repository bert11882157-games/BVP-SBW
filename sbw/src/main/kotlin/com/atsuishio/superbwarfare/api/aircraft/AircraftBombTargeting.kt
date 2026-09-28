package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * GPS waypoints set from the vehicle control terminal. The server owns them and revalidates the pilot.
 *
 * The vehicle holds one waypoint slot per GPS munition it still carries (GPS bombs and unguided-seeker cruise
 * missiles). Every GPS release flies to the first assigned waypoint and wipes it, so the aircraft works as a launch
 * pad for as many separate targets as it has rounds.
 */
object AircraftBombTargeting {
    private const val LEGACY_KEY = "BvpBombGpsTarget"
    private const val KEY = "BvpGpsWaypoints"
    /** Most waypoints kept (the terminal lists at most 32 stations). */
    const val MAX_SLOTS = 32

    /** A store that flies to a GPS waypoint when it is released. */
    @JvmStatic fun isGpsStore(store: JsonObject?): Boolean {
        if (store == null) return false
        if (store.getAsJsonObject("Bomb")?.get("Mode")?.asString == "GPS") return true
        return store["Category"]?.asString == "CRUISE" && store.has("Flight") && !store.has("Guidance")
    }

    /** GPS munitions still on the vehicle: the number of waypoint slots. */
    @JvmStatic fun gpsCapacity(aircraft: Entity): Int {
        val vehicle = aircraft as? VehicleEntity ?: return 0
        val definition = AircraftArmamentManager.definition(vehicle) ?: return 0
        var total = 0
        for (mount in AircraftArmamentRegistry.mounts(definition)) {
            val id = mount["Id"]?.asString ?: continue
            if (isGpsStore(AircraftArmamentManager.equippedStore(vehicle, id)))
                total += AircraftArmamentManager.mountRemaining(vehicle, id)
        }
        return total.coerceIn(0, MAX_SLOTS)
    }

    /** Waypoint slots, one per GPS munition carried; null entries are unassigned. */
    @JvmStatic fun gpsSlots(aircraft: Entity): List<BlockPos?> {
        val vehicle = aircraft as? VehicleEntity ?: return emptyList()
        val stored = read(vehicle)
        val size = maxOf(gpsCapacity(vehicle), 0)
        return List(size) { stored.getOrNull(it) }
    }

    /** Assigns waypoint [slot] (0-based) to [target]. */
    @JvmStatic fun setGpsTarget(player: ServerPlayer, aircraft: Entity, slot: Int, target: BlockPos): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        if (!operator(player, vehicle) || AircraftArmamentManager.definition(vehicle) == null ||
            target.y !in player.serverLevel().minBuildHeight until player.serverLevel().maxBuildHeight ||
            kotlin.math.abs(target.x) > 30_000_000 || kotlin.math.abs(target.z) > 30_000_000) return false
        if (slot !in 0 until maxOf(1, gpsCapacity(vehicle))) return false
        val slots = read(vehicle).toMutableList()
        while (slots.size <= slot) slots.add(null)
        slots[slot] = target
        write(vehicle, slots)
        return true
    }

    /** Diagnostics only (war harness): the next GPS release flies to [target]. */
    @JvmStatic fun setDiagnosticGpsTarget(aircraft: VehicleEntity, target: BlockPos) {
        check(java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios"))
        write(aircraft, listOf(target))
    }

    /** Older terminals: a single waypoint goes into the first free slot (or replaces the first). */
    @JvmStatic fun setGpsTarget(player: ServerPlayer, aircraft: Entity, target: BlockPos): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        val free = gpsSlots(vehicle).indexOfFirst { it == null }
        return setGpsTarget(player, aircraft, if (free >= 0) free else 0, target)
    }

    @JvmStatic fun clearGpsTarget(player: ServerPlayer, aircraft: Entity, slot: Int): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        if (!operator(player, vehicle)) return false
        val slots = read(vehicle).toMutableList()
        if (slot !in slots.indices) return false
        slots[slot] = null
        write(vehicle, slots)
        return true
    }

    @JvmStatic fun clearGpsTarget(player: ServerPlayer, aircraft: Entity): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        if (!operator(player, vehicle)) return false
        write(vehicle, emptyList())
        return true
    }

    /** The waypoint the next GPS release will fly to, without using it up. */
    @JvmStatic fun gpsTarget(vehicle: VehicleEntity): Vec3? =
        read(vehicle).take(gpsCapacity(vehicle)).firstOrNull { it != null }?.center

    /**
     * Wipes the waypoint [gpsTarget] returned, once the munition flying to it has left the vehicle. The slot goes with
     * it (one munition fewer), so the waypoints after it move up.
     */
    @JvmStatic fun consumeGpsTarget(vehicle: VehicleEntity, used: Vec3?) {
        if (used == null) return
        val slots = read(vehicle).toMutableList()
        val index = slots.indexOfFirst { it != null && it.center == used }
        if (index < 0) return
        slots.removeAt(index)
        write(vehicle, slots)
    }

    private fun operator(player: ServerPlayer, vehicle: VehicleEntity) =
        player.vehicle === vehicle && vehicle.getSeatIndex(player) == 0 && player.level() === vehicle.level() &&
            player.isAlive && !player.isSpectator && vehicle.isAlive && !vehicle.isWreck

    private fun read(vehicle: VehicleEntity): List<BlockPos?> {
        val data = vehicle.persistentData
        if (data.contains(LEGACY_KEY)) {
            // Single waypoint saved by an older version: becomes slot 0.
            val legacy = BlockPos.of(data.getLong(LEGACY_KEY))
            data.remove(LEGACY_KEY)
            write(vehicle, listOf(legacy) + readList(data).drop(1))
        }
        return readList(data)
    }

    private fun readList(data: CompoundTag): List<BlockPos?> {
        if (!data.contains(KEY, Tag.TAG_LIST.toInt())) return emptyList()
        val list = data.getList(KEY, Tag.TAG_COMPOUND.toInt())
        return List(minOf(list.size, MAX_SLOTS)) { i ->
            val entry = list.getCompound(i)
            if (entry.contains("Pos", Tag.TAG_LONG.toInt())) BlockPos.of(entry.getLong("Pos")) else null
        }
    }

    private fun write(vehicle: VehicleEntity, slots: List<BlockPos?>) {
        val trimmed = slots.take(MAX_SLOTS).dropLastWhile { it == null }
        if (trimmed.isEmpty()) { vehicle.persistentData.remove(KEY); return }
        val list = net.minecraft.nbt.ListTag()
        for (pos in trimmed) list.add(CompoundTag().also { if (pos != null) it.putLong("Pos", pos.asLong()) })
        vehicle.persistentData.put(KEY, list)
    }
}
