package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A finite server-owned submunition release; children cannot recursively split. */
object AircraftClusterBomb {
    private const val KEY = "BvpCluster"
    private val CHILD_PROFILE = ResourceLocation("berts_vehicle_pack", "cluster_bomblet")
    fun configured(bomb: AerialBombEntity): Boolean = bomb.persistentData.contains(KEY)
    data class Configuration(val count: Int, val releaseHeight: Double, val spreadSpeed: Double,
        val damage: Float, val radius: Float, val lifetime: Int) {
        init {
            require(count in 1..24 && releaseHeight.isFinite() && releaseHeight in 2.0..32.0)
            require(spreadSpeed.isFinite() && spreadSpeed in 0.0..1.0)
            require(damage.isFinite() && damage in 0f..2000f && radius.isFinite() && radius in 0.1f..8f)
            require(lifetime in 20..200)
        }
    }
    @JvmStatic fun configure(bomb: AerialBombEntity, json: JsonObject) {
        require(!bomb.level().isClientSide)
        val config = Configuration(json["Count"].asInt, json["ReleaseHeight"].asDouble,
            json["SpreadSpeed"].asDouble, json["BombletDamage"].asFloat,
            json["BombletRadius"].asFloat, json["LifetimeTicks"].asInt)
        val tag = net.minecraft.nbt.CompoundTag()
        tag.putInt("Count", config.count); tag.putDouble("Height", config.releaseHeight)
        tag.putDouble("Spread", config.spreadSpeed); tag.putFloat("Damage", config.damage)
        tag.putFloat("Radius", config.radius); tag.putInt("Life", config.lifetime)
        bomb.persistentData.put(KEY, tag)
        // Only the released bomblets deliver blast damage, including when the casing is shot.
        bomb.explosionDamageValue = 0f
        bomb.explosionRadiusValue = 0f
    }
    fun tick(bomb: AerialBombEntity): Boolean {
        val level = bomb.level() as? ServerLevel ?: return false
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
    /** Also handles a very low release/impact without detonating the container as an HE bomb. */
    fun release(bomb: AerialBombEntity, at: Vec3): Boolean {
        val level = bomb.level() as? ServerLevel ?: return false
        val data = bomb.persistentData
        if (!data.contains(KEY)) return false
        if (data.getBoolean("BvpClusterReleased")) return true
        data.putBoolean("BvpClusterReleased", true)
        val tag = data.getCompound(KEY)
        val config = try { Configuration(tag.getInt("Count"), tag.getDouble("Height"),
            tag.getDouble("Spread"), tag.getFloat("Damage"), tag.getFloat("Radius"), tag.getInt("Life"))
        } catch (_: IllegalArgumentException) { bomb.discard(); return true }
        val aircraft = if (data.hasUUID("BvpBombAircraft")) data.getUUID("BvpBombAircraft") else bomb.uuid
        val motion = bomb.deltaMovement
        var spawned = 0
        for (offset in spread(config.count, config.spreadSpeed, level.random.nextDouble() * Math.PI * 2)) {
            val child = ModEntities.SC_50.get().create(level) ?: continue
            child.owner = bomb.owner
            child.setPos(at.x, at.y + 0.15, at.z)
            child.deltaMovement = motion.add(offset)
            child.lifeValue = config.lifetime
            child.configure("DUMB", aircraft, bomb.gravityValue, 1.0, 0.0, config.damage, config.radius, null)
            ProjectileProfiles.assign(child, CHILD_PROFILE)
            if (level.addFreshEntity(child)) spawned++
        }
        AircraftMunitionDebug.log(bomb, "cluster release bomblets=$spawned lifetime=${config.lifetime}")
        bomb.discard()
        return true
    }
}
