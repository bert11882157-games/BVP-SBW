package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A finite server-owned submunition release; children cannot recursively split. */
object AircraftClusterBomb {
    private const val KEY = "BvpCluster"
    private const val SENSOR_KEY = "BvpClusterSensor"
    private const val TYPED_CHILD_KEY = "BvpClusterTypedChild"
    private val CHILD_PROFILE = ResourceLocation("berts_vehicle_pack", "cluster_bomblet")
    fun configured(bomb: AerialBombEntity): Boolean = bomb.persistentData.contains(KEY)
    fun isTypedChild(bomb: AerialBombEntity): Boolean = bomb.persistentData.getBoolean(TYPED_CHILD_KEY)
    fun isSensorDispenser(bomb: AerialBombEntity): Boolean = bomb.persistentData.contains(SENSOR_KEY)
    data class Configuration(val count: Int, val releaseHeight: Double, val spreadSpeed: Double,
        val damage: Float, val radius: Float, val lifetime: Int, val mode: String = "HE",
        val bombletProfile: ResourceLocation? = null, val sensorRadius: Double = 0.0,
        val sensorShots: Int = 0, val sensorProjectileProfile: ResourceLocation? = null) {
        init {
            require(count in 1..24 && releaseHeight.isFinite() && releaseHeight in 2.0..32.0)
            require(spreadSpeed.isFinite() && spreadSpeed in 0.0..1.0)
            require(damage.isFinite() && damage in 0f..2000f && radius.isFinite() && radius in 0.1f..8f)
            require(lifetime in 20..200)
            require(mode in setOf("HE", "HEAT", "SENSOR_FUZED"))
            require((mode == "HEAT") == (bombletProfile != null))
            require((mode == "SENSOR_FUZED") == (sensorProjectileProfile != null))
            if (mode == "SENSOR_FUZED") require(sensorRadius in 2.0..16.0 && sensorShots in 1..4)
            else require(sensorRadius == 0.0 && sensorShots == 0)
            if (mode != "HE") require(damage == 0f)
        }
    }
    @JvmStatic fun configure(bomb: AerialBombEntity, json: JsonObject) {
        require(!bomb.level().isClientSide)
        val mode = json["Mode"]?.asString ?: "HE"
        fun profile(key: String): ResourceLocation? = json[key]?.asString?.let {
            ResourceLocation.tryParse(it) ?: error("Invalid cluster $key")
        }
        val config = Configuration(json["Count"].asInt, json["ReleaseHeight"].asDouble,
            json["SpreadSpeed"].asDouble, json["BombletDamage"].asFloat,
            json["BombletRadius"].asFloat, json["LifetimeTicks"].asInt, mode,
            profile("BombletProfile"), json["SensorRadius"]?.asDouble ?: 0.0,
            json["SensorShots"]?.asInt ?: 0, profile("SensorProjectileProfile"))
        val tag = CompoundTag()
        tag.putInt("Count", config.count); tag.putDouble("Height", config.releaseHeight)
        tag.putDouble("Spread", config.spreadSpeed); tag.putFloat("Damage", config.damage)
        tag.putFloat("Radius", config.radius); tag.putInt("Life", config.lifetime)
        tag.putString("Mode", config.mode)
        config.bombletProfile?.let { tag.putString("BombletProfile", it.toString()) }
        config.sensorProjectileProfile?.let { tag.putString("SensorProjectileProfile", it.toString()) }
        if (mode == "SENSOR_FUZED") {
            tag.putDouble("SensorRadius", config.sensorRadius)
            tag.putInt("SensorShots", config.sensorShots)
        }
        bomb.persistentData.put(KEY, tag)
        // Only the released bomblets deliver blast damage, including when the casing is shot.
        bomb.explosionDamageValue = 0f
        bomb.explosionRadiusValue = 0f
    }
    fun tick(bomb: AerialBombEntity): Boolean {
        val level = bomb.level() as? ServerLevel ?: return false
        if (isSensorDispenser(bomb)) return sensorTick(bomb, level)
        if (!bomb.persistentData.contains(KEY) || bomb.tickCount < 4 || bomb.tickCount % 2 != 0 ||
            bomb.deltaMovement.y >= 0.0) return false
        val height = bomb.persistentData.getCompound(KEY).getDouble("Height").coerceIn(2.0, 32.0)
        // A vertical ray stays in the bomb's current loaded chunk; no terrain search or extra tickets.
        val point = level.clip(ClipContext(bomb.position(), bomb.position().add(0.0, -height, 0.0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bomb))
        return point.type != HitResult.Type.MISS && release(bomb, bomb.position())
    }
    internal fun spread(count: Int, speed: Double, phase: Double): List<Vec3> {
        require(count in 1..24 && speed.isFinite() && speed in 0.0..1.0 && phase.isFinite())
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        return (0 until count).map { index ->
            val radius = speed * sqrt((index + 0.5) / count)
            val angle = phase + goldenAngle * index
            Vec3(cos(angle) * radius, -0.04, sin(angle) * radius)
        }
    }
    private fun sensorTick(dispenser: AerialBombEntity, level: ServerLevel): Boolean {
        if (dispenser.tickCount % 4 != 0) return true
        val tag = dispenser.persistentData.getCompound(SENSOR_KEY)
        val remaining = tag.getInt("Shots")
        if (remaining <= 0) { dispenser.discard(); return true }
        val radius = tag.getDouble("Radius").coerceIn(2.0, 16.0)
        val profile = ResourceLocation.tryParse(tag.getString("Profile"))
        if (profile == null || ProjectileProfiles.resolve(profile)?.combat == null) {
            dispenser.discard(); return true
        }
        val candidates = level.getEntitiesOfClass(VehicleEntity::class.java,
            AABB.ofSize(dispenser.position(), radius * 2, radius * 2, radius * 2)) { target ->
            target.isAlive && !AircraftProjectileDamage.isAircraft(target) &&
                target !== dispenser.owner?.vehicle && target.y <= dispenser.y + 1.0 &&
                target.position().distanceToSqr(dispenser.position()) <= radius * radius
        }
        val target = candidates.sortedBy { it.position().distanceToSqr(dispenser.position()) }
            .firstOrNull { vehicle ->
                level.clip(ClipContext(dispenser.position(), vehicle.boundingBox.center,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, dispenser)).type == HitResult.Type.MISS
            } ?: return true
        val child = ModEntities.SC_50.get().create(level) ?: return true
        child.owner = dispenser.owner
        child.setPos(dispenser.x, dispenser.y, dispenser.z)
        child.deltaMovement = target.boundingBox.center.subtract(dispenser.position()).normalize().scale(1.6)
        child.lifeValue = 30
        val aircraft = dispenser.persistentData.takeIf { it.hasUUID("BvpBombAircraft") }
            ?.getUUID("BvpBombAircraft") ?: dispenser.uuid
        child.configure("DUMB", aircraft, 0.01f, 0.0, 0.0, 0f, 0f, null)
        child.persistentData.putBoolean(TYPED_CHILD_KEY, true)
        ProjectileProfiles.assign(child, profile)
        if (level.addFreshEntity(child)) {
            tag.putInt("Shots", remaining - 1)
            if (remaining == 1) dispenser.discard()
        }
        return true
    }

    /** A dispenser that reaches terrain is inert; only its fired skeets carry damage. */
    fun consumeSensorImpact(bomb: AerialBombEntity): Boolean {
        if (!isSensorDispenser(bomb)) return false
        bomb.discard()
        return true
    }

    /** Also handles a very low release/impact without detonating the container as an HE bomb. */
    fun release(bomb: AerialBombEntity, at: Vec3): Boolean {
        val level = bomb.level() as? ServerLevel ?: return false
        val data = bomb.persistentData
        if (!data.contains(KEY)) return false
        if (data.getBoolean("BvpClusterReleased")) return true
        data.putBoolean("BvpClusterReleased", true)
        val tag = data.getCompound(KEY)
        val config = try { Configuration(tag.getInt("Count"), tag.getDouble("Height"),
            tag.getDouble("Spread"), tag.getFloat("Damage"), tag.getFloat("Radius"), tag.getInt("Life"),
            tag.getString("Mode").ifBlank { "HE" },
            tag.getString("BombletProfile").takeIf { it.isNotBlank() }?.let(ResourceLocation::tryParse),
            tag.getDouble("SensorRadius"), tag.getInt("SensorShots"),
            tag.getString("SensorProjectileProfile").takeIf { it.isNotBlank() }?.let(ResourceLocation::tryParse))
        } catch (_: IllegalArgumentException) { bomb.discard(); return true }
        val profile = when (config.mode) {
            "HEAT" -> config.bombletProfile
            "SENSOR_FUZED" -> config.sensorProjectileProfile
            else -> CHILD_PROFILE
        }
        if (profile == null || (config.mode != "HE" && ProjectileProfiles.resolve(profile)?.combat == null)) {
            bomb.discard(); return true
        }
        val aircraft = if (data.hasUUID("BvpBombAircraft")) data.getUUID("BvpBombAircraft") else bomb.uuid
        val motion = bomb.deltaMovement
        var spawned = 0
        for (offset in spread(config.count, config.spreadSpeed, level.random.nextDouble() * Math.PI * 2)) {
            val child = ModEntities.SC_50.get().create(level) ?: continue
            child.owner = bomb.owner
            child.setPos(at.x, at.y + 0.15, at.z)
            child.deltaMovement = motion.add(offset)
            child.lifeValue = config.lifetime
            val sensor = config.mode == "SENSOR_FUZED"
            child.configure("DUMB", aircraft, if (sensor) 0.025f else bomb.gravityValue,
                1.0, 0.0, if (sensor) 0f else config.damage, if (sensor) 0f else config.radius, null)
            if (sensor) {
                val sensorTag = CompoundTag()
                sensorTag.putDouble("Radius", config.sensorRadius)
                sensorTag.putInt("Shots", config.sensorShots)
                sensorTag.putString("Profile", profile.toString())
                child.persistentData.put(SENSOR_KEY, sensorTag)
                ProjectileProfiles.assign(child, CHILD_PROFILE)
            } else {
                if (config.mode == "HEAT") child.persistentData.putBoolean(TYPED_CHILD_KEY, true)
                ProjectileProfiles.assign(child, profile)
            }
            if (level.addFreshEntity(child)) spawned++
        }
        AircraftMunitionDebug.log(bomb, "cluster release mode=${config.mode} bomblets=$spawned lifetime=${config.lifetime}")
        bomb.discard()
        return true
    }
}
