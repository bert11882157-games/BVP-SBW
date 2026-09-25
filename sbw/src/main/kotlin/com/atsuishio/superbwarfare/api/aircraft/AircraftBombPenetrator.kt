package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Explosion
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.ForgeEventFactory
import net.minecraftforge.event.level.ExplosionEvent

/** Bounded hard-target travel followed by a server-owned delayed fuze. */
object AircraftBombPenetrator {
    private const val KEY = "BvpBombPenetrator"
    private const val FUZING_KEY = "BvpBombFuzing"
    private const val DIRECTION_X = "DirX"
    private const val DIRECTION_Y = "DirY"
    private const val DIRECTION_Z = "DirZ"

    /**
     * Travel direction of a penetrator-fuzed bomb for its TNT fireball cylinder: recorded at impact, else the
     * current velocity, else straight down. Null for bombs without a Penetrator fuze.
     */
    fun direction(bomb: AerialBombEntity): Vec3? {
        val data = bomb.persistentData
        if (!data.contains(KEY)) return null
        val tag = data.getCompound(KEY)
        if (tag.contains(DIRECTION_X)) {
            val stored = Vec3(tag.getDouble(DIRECTION_X), tag.getDouble(DIRECTION_Y), tag.getDouble(DIRECTION_Z))
            if (stored.lengthSqr() > 1.0e-6) return stored.normalize()
        }
        val velocity = bomb.deltaMovement
        return if (velocity.lengthSqr() > 1.0e-6) velocity.normalize() else Vec3(0.0, -1.0, 0.0)
    }
    fun isFuzing(bomb: AerialBombEntity): Boolean = bomb.persistentData.contains(FUZING_KEY)
    // FFA/Dominions is a runtime dependency, but SBW has no compile-time dependency on it.
    // A missing or failing bridge denies penetration instead of bypassing a protected claim.
    private val protectedAtMethod by lazy {
        runCatching { Class.forName("dev.ffafactions.FactionsApi")
            .getMethod("protectedAt", ServerLevel::class.java, BlockPos::class.java) }.getOrNull()
    }

    private fun protectedAt(level: ServerLevel, pos: BlockPos): Boolean = runCatching {
        protectedAtMethod?.invoke(null, level, pos) as? Boolean ?: true
    }.getOrDefault(true)

    private fun permittedToBreak(level: ServerLevel, bomb: AerialBombEntity, pos: BlockPos): Boolean {
        if (protectedAt(level, pos)) return false
        val explosion = Explosion(level, bomb, pos.x + .5, pos.y + .5, pos.z + .5,
            0f, false, Explosion.BlockInteraction.DESTROY)
        if (ForgeEventFactory.onExplosionStart(level, explosion)) return false
        explosion.toBlow.add(pos)
        MinecraftForge.EVENT_BUS.post(ExplosionEvent.Detonate(level, explosion, mutableListOf()))
        return pos in explosion.toBlow && !protectedAt(level, pos)
    }

    data class Configuration(val maxDepthBlocks: Int, val maxBlockHardness: Double, val fuzeDelayTicks: Int) {
        init {
            require(maxDepthBlocks in 1..8)
            require(maxBlockHardness.isFinite() && maxBlockHardness in 0.1..50.0)
            require(fuzeDelayTicks in 1..20)
        }
    }

    fun configure(bomb: AerialBombEntity, json: JsonObject) {
        require(!bomb.level().isClientSide)
        val config = Configuration(json["MaxDepthBlocks"].asInt, json["MaxBlockHardness"].asDouble,
            json["FuzeDelayTicks"].asInt)
        bomb.persistentData.put(KEY, CompoundTag().apply {
            putInt("Depth", config.maxDepthBlocks)
            putDouble("Hardness", config.maxBlockHardness)
            putInt("Delay", config.fuzeDelayTicks)
        })
    }

    fun begin(bomb: AerialBombEntity, hit: BlockHitResult): Boolean {
        val level = bomb.level() as? ServerLevel ?: return false
        val tag = bomb.persistentData.getCompound(KEY)
        if (!bomb.persistentData.contains(KEY) || bomb.persistentData.contains(FUZING_KEY)) return false
        val config = try { Configuration(tag.getInt("Depth"), tag.getDouble("Hardness"),
            tag.getInt("Delay")) } catch (_: IllegalArgumentException) { return false }
        val velocity = bomb.deltaMovement
        val direction = if (velocity.lengthSqr() > 1.0e-6) velocity.normalize()
            else Vec3.atLowerCornerOf(hit.direction.opposite.normal).normalize()
        var detonation = hit.location
        var previous: BlockPos? = null
        var crossed = 0
        // Quarter-block sampling bounds travel even for steep or diagonal impacts.
        for (step in 1..(config.maxDepthBlocks * 4)) {
            val point = hit.location.add(direction.scale(step * 0.25))
            val pos = BlockPos.containing(point)
            if (pos == previous) continue
            previous = pos
            if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) break
            val state = level.getBlockState(pos)
            if (state.isAir) {
                if (crossed > 0) { detonation = point; break }
                continue
            }
            val hardness = state.getDestroySpeed(level, pos).toDouble()
            if (!hardness.isFinite() || hardness < 0.0 || hardness > config.maxBlockHardness) break
            if (!ExplosionConfig.EXPLOSION_DESTROY.get() || !permittedToBreak(level, bomb, pos)) break
            if (!level.destroyBlock(pos, false)) break
            crossed++
            detonation = point
            if (crossed >= config.maxDepthBlocks) break
        }
        tag.putDouble(DIRECTION_X, direction.x)
        tag.putDouble(DIRECTION_Y, direction.y)
        tag.putDouble(DIRECTION_Z, direction.z)
        bomb.persistentData.putInt(FUZING_KEY, config.fuzeDelayTicks)
        bomb.noPhysics = true
        bomb.setGravity(0f)
        bomb.deltaMovement = Vec3.ZERO
        bomb.setPos(detonation.x, detonation.y, detonation.z)
        AircraftMunitionDebug.log(bomb, "penetrator embedded blocks=$crossed fuzeTicks=${config.fuzeDelayTicks}")
        return true
    }

    /** Returns true while an embedded bomb is fuzing, including its detonation tick. */
    fun tick(bomb: AerialBombEntity): Boolean {
        if (bomb.level().isClientSide || !bomb.persistentData.contains(FUZING_KEY)) return false
        bomb.deltaMovement = Vec3.ZERO
        bomb.noPhysics = true
        val remaining = bomb.persistentData.getInt(FUZING_KEY) - 1
        if (remaining > 0) bomb.persistentData.putInt(FUZING_KEY, remaining)
        else {
            bomb.persistentData.remove(FUZING_KEY)
            bomb.causeExplode(bomb.position())
            bomb.discard()
        }
        return true
    }
}
