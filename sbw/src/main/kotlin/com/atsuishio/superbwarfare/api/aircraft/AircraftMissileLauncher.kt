package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Optional FFA linkage. FFA owns target selection, consecutive dwell and final launch admission. */
object AircraftMissileLauncher {
    data class Guidance(val mode: String, val lockTicks: Int, val range: Double,
        val cone: Double, val vulnerability: Double)
    fun guidance(store: JsonObject): Guidance? {
        val raw = store.getAsJsonObject("Guidance") ?: return null
        val mode = raw["Mode"].asString
        val ticks = raw["LockTicks"].asDouble
        val range = raw["Range"].asDouble
        val cone = raw["ConeDegrees"].asDouble
        val vulnerability = raw["CountermeasureVulnerability"].asDouble
        require(mode in setOf("ACTIVE_RADAR", "SEMI_ACTIVE_RADAR", "INFRARED", "ANTI_RADIATION", "GROUND_INFRARED", "ACTIVE_SURFACE_RADAR"))
        require(ticks.isFinite() && ticks == kotlin.math.floor(ticks) && ticks in 0.0..200.0)
        require(mode !in setOf("INFRARED", "GROUND_INFRARED") || ticks >= 1)
        require(range.isFinite() && range in 16.0..4096.0 && cone.isFinite() && cone in 1.0..60.0)
        require(vulnerability.isFinite() && vulnerability in 0.0..2.0)
        return Guidance(mode, ticks.toInt(), range, cone, vulnerability)
    }
    private data class Api(val update: java.lang.reflect.Method, val launch: java.lang.reflect.Method,
        val launchProfile: java.lang.reflect.Method?,
        val clear: java.lang.reflect.Method, val clearChannel: java.lang.reflect.Method, val state: java.lang.reflect.Method)
    private val api by lazy {
        runCatching {
            val cls = Class.forName("dev.ballistics.AircraftMissileHooks")
            Api(cls.getMethod("updateLock", Entity::class.java, Entity::class.java, String::class.java, Vec3::class.java,
                String::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType),
                cls.getMethod("launch", Entity::class.java, Entity::class.java, String::class.java, Vec3::class.java, Vec3::class.java,
                    String::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType),
                runCatching { cls.getMethod("launch", Entity::class.java, Entity::class.java, String::class.java,
                    Vec3::class.java, Vec3::class.java, String::class.java, Int::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                    CompoundTag::class.java) }.getOrNull(),
                cls.getMethod("clearLock", Entity::class.java), cls.getMethod("clearLock", Entity::class.java, String::class.java),
                cls.getMethod("getLockState", Entity::class.java, String::class.java))
        }.getOrNull()
    }
    internal fun visualModel(store: JsonObject): String? {
        val path = store["Model"]?.asString ?: return null
        return Regex("berts_vehicle_pack:custom_geo/aircraft_stores/([a-z0-9_]+)\\.geo\\.json")
            .matchEntire(path)?.groupValues?.get(1)
    }
    private fun forward(vehicle: VehicleEntity): Vec3 {
        val d = vehicle.getVehicleTransform(1f).transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
        return Vec3(d.x, d.y, d.z)
    }
    fun clear(vehicle: VehicleEntity) { runCatching { api?.clear?.invoke(null, vehicle) } }
    fun clear(vehicle: VehicleEntity, channel: String) { runCatching { api?.clearChannel?.invoke(null, vehicle, channel) } }
    fun state(vehicle: VehicleEntity, channel: String): CompoundTag = runCatching {
        api?.state?.invoke(null, vehicle, channel) as? CompoundTag ?: CompoundTag()
    }.getOrDefault(CompoundTag())
    /** -1 missing/incompatible FFA, 0 seeking, 1 acquiring, 2 locked. */
    fun update(vehicle: VehicleEntity, player: ServerPlayer, channel: String, store: JsonObject): Int = runCatching {
        val p = guidance(store) ?: return -1
        (api?.update?.invoke(null, vehicle, player, channel, forward(vehicle), p.mode, p.lockTicks, p.range, p.cone) as? Int) ?: -1
    }.getOrDefault(-1)
    /** Lock toward an explicit world [forward] (a turret or launcher bore) instead of the hull nose. */
    fun updateToward(vehicle: VehicleEntity, player: ServerPlayer, channel: String, forward: Vec3, store: JsonObject): Int = runCatching {
        val p = guidance(store) ?: return -1
        (api?.update?.invoke(null, vehicle, player, channel, forward, p.mode, p.lockTicks, p.range, p.cone) as? Int) ?: -1
    }.getOrDefault(-1)
    fun available(): Boolean = api?.launchProfile != null
    /** Launch from a world [origin] along a world [forward] (a ground launcher's muzzle frame). */
    fun launchAt(vehicle: VehicleEntity, player: ServerPlayer, channel: String, origin: Vec3, forward: Vec3,
                 store: JsonObject): Boolean = runCatching {
        val p = guidance(store) ?: return false
        val profile = flightProfile(store)
        val tnt = com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts.storeCharge(store)
        val method = api?.launchProfile ?: return false
        com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts.launch(vehicle.level(), tnt) {
            method.invoke(null, vehicle, player, channel, origin, forward, p.mode, p.lockTicks, p.range, p.cone,
                p.vulnerability, profile) == true
        }
    }.getOrDefault(false)
    private fun flightProfile(store: JsonObject): CompoundTag {
        val flight = store.getAsJsonObject("Flight")
        val profile = CompoundTag()
        for (key in listOf("InitialSpeed", "MaxSpeed", "AccelerationPerTick", "TurnDegreesPerSecond", "Damage", "BlastRadius",
            "MaxLoadFactorG", "BodyTurnLimitScale"))
            flight?.get(key)?.let { profile.putDouble(key, it.asDouble) }
        visualModel(store)?.let { profile.putString("VisualModel", it) }
        return profile
    }
    /** [launchOffset]: hull offset from [mount] to the launch point (the pylon layout's); null uses LaunchOffset. */
    fun launch(vehicle: VehicleEntity, player: ServerPlayer, channel: String, mount: Vec3, store: JsonObject,
               launchOffset: Vec3? = null): Boolean = runCatching {
        val p = guidance(store) ?: return false
        val offset = launchOffset
            ?: if (store.has("LaunchOffset")) requireNotNull(AircraftArmamentRegistry.vector(store["LaunchOffset"])) else Vec3.ZERO
        val local = mount.add(offset)
        val position = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        val profile = flightProfile(store)
        // FFA simulates the missile; SBW stamps the TNT charge on the entity it spawns (see ExternalMunitionBlasts).
        val tnt = com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts.storeCharge(store)
        val method = if (profile.isEmpty) api?.launch else api?.launchProfile
        val args = arrayOf(vehicle, player, channel, Vec3(position.x, position.y, position.z), forward(vehicle),
            p.mode, p.lockTicks, p.range, p.cone, p.vulnerability)
        com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts.launch(vehicle.level(), tnt) {
            if (method == api?.launchProfile) method?.invoke(null, *args, profile) == true
            else method?.invoke(null, *args) == true
        }
    }.getOrDefault(false)
}
