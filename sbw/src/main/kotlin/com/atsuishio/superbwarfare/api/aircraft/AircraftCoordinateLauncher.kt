package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import java.lang.reflect.Method

/** Optional FFA coordinate launch adapter, shared by authored ground and aircraft armaments. */
object AircraftCoordinateLauncher {
    fun isCoordinate(category: String?): Boolean =
        category == "CRUISE_MISSILE" || category == "COORDINATE_MISSILE"

    fun profile(store: JsonObject): String? {
        if (!isCoordinate(store["Category"]?.asString)) return null
        val value = store["CoordinateProfile"] ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.takeIf { it.length <= 128 && ResourceLocation.tryParse(it) != null }
    }

    private var warned = false
    private fun warn(failure: Throwable) {
        if (!warned) {
            warned = true
            Mod.LOGGER.warn("FFA coordinate missile integration unavailable", failure)
        }
    }

    private val api: Method? by lazy {
        try {
            Class.forName("dev.ballistics.VehicleCoordinateMissileHooks").getMethod("launch",
                Entity::class.java, Entity::class.java, String::class.java,
                Vec3::class.java, Vec3::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        } catch (_: ClassNotFoundException) { null }
        catch (failure: ReflectiveOperationException) { warn(failure); null }
        catch (failure: LinkageError) { warn(failure); null }
    }

    /** No fallback to laser guidance or an unguided projectile when a target or FFA is absent. */
    fun launch(vehicle: VehicleEntity, player: ServerPlayer, slot: String, localMount: Vec3,
               store: JsonObject): Boolean {
        if (vehicle.level().isClientSide || player.level() !== vehicle.level()) return false
        val profile = profile(store) ?: return false
        val method = api ?: return false
        val offset = if (store.has("LaunchOffset"))
            AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: return false else Vec3.ZERO
        val localDirection = if (store.has("LaunchDirection"))
            AircraftArmamentRegistry.vector(store["LaunchDirection"]) ?: return false else Vec3(0.0, 0.0, 1.0)
        if (localDirection.lengthSqr() < 1e-12 || offset.length() > 8.0) return false
        val mount = localMount.add(offset)
        val transform = vehicle.getVehicleTransform(1f)
        val origin = transform.transformPosition(Vector3d(mount.x, mount.y, mount.z))
        val direction = transform.transformDirection(Vector3d(localDirection.x, localDirection.y,
            localDirection.z)).normalize()
        return try {
            method.invoke(null, vehicle, player, slot, Vec3(origin.x, origin.y, origin.z),
                Vec3(direction.x, direction.y, direction.z), profile,
                store["Category"].asString == "CRUISE_MISSILE") == true
        } catch (failure: ReflectiveOperationException) { warn(failure); false }
        catch (failure: LinkageError) { warn(failure); false }
    }
}
