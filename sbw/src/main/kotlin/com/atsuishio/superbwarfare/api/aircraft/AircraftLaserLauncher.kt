package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.gun.ProjectileFactory
import com.atsuishio.superbwarfare.item.gun.resolvedProfileId
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Mount positions are vehicle-local blocks; called only after server equipment/capacity admission. */
object AircraftLaserLauncher {
    @JvmStatic fun launch(vehicle: VehicleEntity, player: ServerPlayer, localMount: Vec3,
        store: JsonObject): Boolean {
        if (vehicle.level().isClientSide || player.level() !== vehicle.level() || !vehicle.isAlive) return false
        val launchId = store["LaunchGunProfile"]?.asString ?: return false
        val rawGun = CustomData.GUN_DATA[launchId] ?: return false
        val profileId = store["ProjectileProfile"]?.asString?.let(ResourceLocation::tryParse) ?: return false
        val flight = ProjectileProfiles.resolve(profileId)?.guidedPropulsion ?: return false
        if (!flight.isValid()) return false
        val data = GunData.from(ItemStack(ModItems.VEHICLE_GUN.get())) { rawGun }
        if (data.get(GunProp.PROJECTILE).resolvedProfileId() != profileId) return false
        val offset = if (store.has("LaunchOffset"))
            AircraftArmamentRegistry.vector(store["LaunchOffset"])?.takeIf { it.length() <= 8.0 } ?: return false
            else Vec3.ZERO
        val launchMount = localMount.add(offset)
        val transform = vehicle.getVehicleTransform(1f)
        val position = transform.transformPosition(Vector3d(launchMount.x, launchMount.y, launchMount.z))
        val direction = transform.transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
        val origin = Vec3(position.x, position.y, position.z)
        val forward = Vec3(direction.x, direction.y, direction.z)
        if (!origin.x.isFinite() || !origin.y.isFinite() || !origin.z.isFinite() ||
            !forward.x.isFinite() || !forward.y.isFinite() || !forward.z.isFinite()) return false
        val parameters = ShootParameters(vehicle, player, player.serverLevel(), origin, forward, data,
            0.0, false, null, null, emitNativeSound = false)
        val prepared = ProjectileFactory.prepare(parameters) ?: return false
        val missile = prepared.entity as? WireGuideMissileEntity ?: return false
        if (ProjectileProfiles.profileId(missile) != profileId || ProjectileProfiles.guidedPropulsion(missile) == null) return false
        missile.persistentData.putUUID("BvpLaserAircraft", vehicle.uuid)
        missile.persistentData.putString("BvpLaserDimension", vehicle.level().dimension().location().toString())
        AircraftArmamentManager.laserTarget(missile)
        return ProjectileFactory.spawn(prepared, parameters)
    }
}
