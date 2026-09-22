package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Optional FFA bridge for air-launched, GPS or forward-targeted cruise munitions. */
object AircraftCruiseLauncher {
    private val launchMethod by lazy {
        runCatching { Class.forName("dev.ballistics.AircraftCruiseHooks").getMethod("launch",
            Entity::class.java, Entity::class.java, Vec3::class.java, Vec3::class.java,
            BlockPos::class.java, CompoundTag::class.java) }.getOrNull()
    }

    fun launch(vehicle: VehicleEntity, player: ServerPlayer, mount: Vec3, store: JsonObject): Boolean = runCatching {
        val flight = store.getAsJsonObject("Flight") ?: return false
        val local = mount.add(AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO)
        val origin = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        val heading = vehicle.getVehicleTransform(1f).transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
        val profile = CompoundTag()
        for (key in listOf("InitialSpeed", "MaxSpeed", "AccelerationPerTick", "TurnDegreesPerSecond", "Damage", "BlastRadius", "Range"))
            profile.putDouble(key, flight[key].asDouble)
        val gps = AircraftBombTargeting.gpsTarget(vehicle)?.let { BlockPos.containing(it) }
        launchMethod?.invoke(null, vehicle, player, Vec3(origin.x, origin.y, origin.z),
            Vec3(heading.x, heading.y, heading.z), gps, profile) == true
    }.getOrDefault(false)
}
