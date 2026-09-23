package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.api.aircraft.AircraftDesignationData
import com.atsuishio.superbwarfare.api.aircraft.AircraftMunitionDebug
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.min

open class AerialBombEntity(type: EntityType<out AerialBombEntity>, level: Level) : DestroyableProjectile(type, level) {
    override fun farProjectileMaximumLifetimeTicks(): Int = 2400

    fun hasGuidedFlight(): Boolean = persistentData.getString("BvpBombMode") in setOf("LASER", "GPS")

    /** The same server-authored values drive the live trajectory and the HUD's nominal prediction. */
    fun configure(mode: String, aircraft: java.util.UUID, gravity: Float, drag: Double,
        turnDegrees: Double, damage: Float, radius: Float, gps: Vec3?) {
        require(!level().isClientSide && mode in setOf("DUMB", "LASER", "GPS"))
        setGravity(gravity)
        explosionDamageValue = damage
        explosionRadiusValue = radius
        persistentData.putString("BvpBombMode", mode)
        persistentData.putUUID("BvpBombAircraft", aircraft)
        persistentData.putDouble("BvpBombDrag", drag)
        persistentData.putDouble("BvpBombTurn", turnDegrees)
        gps?.let { persistentData.putDouble("BvpBombGpsX", it.x); persistentData.putDouble("BvpBombGpsY", it.y)
            persistentData.putDouble("BvpBombGpsZ", it.z) }
    }

    override fun tick() {
        super.tick()
        if (isRemoved || level().isClientSide) return
        val data = persistentData
        if (!data.contains("BvpBombMode")) return
        var velocity = deltaMovement
        val drag = data.getDouble("BvpBombDrag").coerceIn(0.0, 20.0)
        val horizontal = (1.0 - 0.01 * drag).coerceIn(0.8, 1.0)
        velocity = Vec3(velocity.x * horizontal, velocity.y, velocity.z * horizontal)
        val target = when (data.getString("BvpBombMode")) {
            "LASER" -> (level() as? ServerLevel)?.let { server ->
                if (data.hasUUID("BvpBombAircraft")) AircraftDesignationData.get(server)
                    .get(data.getUUID("BvpBombAircraft"))?.position else null
            }
            "GPS" -> if (data.contains("BvpBombGpsX")) Vec3(data.getDouble("BvpBombGpsX"),
                data.getDouble("BvpBombGpsY"), data.getDouble("BvpBombGpsZ")) else null
            else -> null
        }
        if (data.getString("BvpBombMode") != "DUMB") {
            val hadTarget = data.getBoolean("BvpBombHadTarget")
            if ((target != null) != hadTarget) {
                data.putBoolean("BvpBombHadTarget", target != null)
                AircraftMunitionDebug.log(this, if (target != null) "bomb guidance acquired" else "bomb guidance lost")
            }
        }
        if (target != null && velocity.lengthSqr() > 0.0025) {
            val horizontalRange = kotlin.math.hypot(target.x - x, target.z - z)
            val height = (y - target.y).coerceAtLeast(0.0)
            val overhead = min(35.0, min(height * 0.5, horizontalRange * 0.8))
            val aim = target.add(0.0, overhead, 0.0).subtract(position())
            if (aim.lengthSqr() > 1.0) {
                val speed = velocity.length()
                val current = velocity.scale(1.0 / speed)
                val desired = aim.normalize()
                val angle = acos(current.dot(desired).coerceIn(-1.0, 1.0))
                val maxTurn = Math.toRadians(data.getDouble("BvpBombTurn").coerceIn(0.0, 15.0)) *
                    min(1.0, speed / 1.5)
                if (angle > 1.0e-6 && maxTurn > 0.0) {
                    val blend = (maxTurn / angle).coerceAtMost(1.0)
                    val turned = current.scale(1.0 - blend).add(desired.scale(blend)).normalize()
                    // Fins redirect existing momentum; they never add thrust or kinetic energy.
                    velocity = turned.scale(speed)
                }
            }
        }
        deltaMovement = velocity
    }
    override fun getDefaultItem(): Item {
        return ModItems.MEDIUM_AERIAL_BOMB.get()
    }

    override fun getSound(): SoundEvent {
        return ModSounds.SHELL_FLY.get()
    }

    override fun getVolume(): Float {
        return 0.7f
    }

    override fun onHitEntity(result: EntityHitResult) {
        val entity = result.entity
        val owner = this.owner
        if (entity == owner || (owner != null && entity == owner.vehicle) || entity is AerialBombEntity) return
        AircraftMunitionDebug.log(this, "bomb entity impact")
        super.onHitEntity(result)
        if (this.level() is ServerLevel) {
            if (ExplosionConfig.EXPLOSION_DESTROY.get() && ExplosionConfig.EXTRA_EXPLOSION_EFFECT.get()) {
                val aabb = AABB(result.getLocation(), result.getLocation()).inflate(5.0)
                BlockPos.betweenClosedStream(aabb).forEach {
                    val hard = this.level().getBlockState(it).block.defaultDestroyTime()
                    if (hard != -1f && Vec3(
                            it.x.toDouble(),
                            it.y.toDouble(),
                            it.z.toDouble()
                        ).distanceTo(result.getLocation()) < 3
                    ) {
                        this.level().destroyBlock(it, true)
                    }
                }
            }

            causeExplode(result.getLocation())
            this.discard()
        }
    }

    override fun onHitBlock(blockHitResult: BlockHitResult) {
        AircraftMunitionDebug.log(this, "bomb block impact")
        super.onHitBlock(blockHitResult)
        if (this.level() is ServerLevel) {
            if (ExplosionConfig.EXPLOSION_DESTROY.get() && ExplosionConfig.EXTRA_EXPLOSION_EFFECT.get()) {
                val aabb = AABB(blockHitResult.getLocation(), blockHitResult.getLocation()).inflate(5.0)
                BlockPos.betweenClosedStream(aabb).forEach {
                    val hard = this.level().getBlockState(it).block.defaultDestroyTime()
                    if (hard != -1f && Vec3(
                            it.x.toDouble(),
                            it.y.toDouble(),
                            it.z.toDouble()
                        ).distanceTo(blockHitResult.getLocation()) < 3
                    ) {
                        this.level().destroyBlock(it, true)
                    }
                }
            }

            causeExplode(blockHitResult.getLocation())
            this.discard()
        }
    }
}
