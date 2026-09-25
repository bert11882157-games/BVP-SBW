package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.api.aircraft.AircraftDesignationData
import com.atsuishio.superbwarfare.api.aircraft.AircraftMunitionDebug
import com.atsuishio.superbwarfare.api.aircraft.AircraftClusterBomb
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombPenetrator
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombFlight
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.min

open class AerialBombEntity(type: EntityType<out AerialBombEntity>, level: Level) : DestroyableProjectile(type, level) {
    override fun farProjectileMaximumLifetimeTicks(): Int = 2400
    override fun farProjectileExplosionRadius(): Double =
        if (AircraftClusterBomb.configured(this) || AircraftClusterBomb.isTypedChild(this) ||
            AircraftClusterBomb.isSensorDispenser(this)) 0.0
        else super.farProjectileExplosionRadius()

    fun hasGuidedFlight(): Boolean = persistentData.getString("BvpBombMode") in setOf("LASER", "GPS", "TV")

    /** The same server-authored values drive the live trajectory and the HUD's nominal prediction. */
    @JvmOverloads fun configure(mode: String, aircraft: java.util.UUID, gravity: Float, drag: Double,
        turnDegrees: Double, damage: Float, radius: Float, gps: Vec3?, target: java.util.UUID? = null) {
        require(!level().isClientSide && mode in setOf("DUMB", "LASER", "GPS", "TV"))
        require(mode != "TV" || target != null)
        setGravity(gravity)
        explosionDamageValue = damage
        explosionRadiusValue = radius
        persistentData.putString("BvpBombMode", mode)
        persistentData.putUUID("BvpBombAircraft", aircraft)
        persistentData.putDouble("BvpBombDrag", drag)
        persistentData.putDouble("BvpBombTurn", turnDegrees)
        target?.let { persistentData.putUUID("BvpBombTarget", it) }
        gps?.let { persistentData.putDouble("BvpBombGpsX", it.x); persistentData.putDouble("BvpBombGpsY", it.y)
            persistentData.putDouble("BvpBombGpsZ", it.z) }
    }

    override fun tick() {
        super.tick()
        if (isRemoved || level().isClientSide) return
        if (AircraftBombPenetrator.tick(this)) return
        if (AircraftClusterBomb.tick(this)) return
        val data = persistentData
        if (!data.contains("BvpBombMode")) return
        var velocity = deltaMovement
        velocity = AircraftBombFlight.applyHorizontalDrag(velocity, data.getDouble("BvpBombDrag"))
        val target = when (data.getString("BvpBombMode")) {
            "LASER" -> (level() as? ServerLevel)?.let { server ->
                if (data.hasUUID("BvpBombAircraft")) AircraftDesignationData.get(server)
                    .get(data.getUUID("BvpBombAircraft"))?.position else null
            }
            "GPS" -> if (data.contains("BvpBombGpsX")) Vec3(data.getDouble("BvpBombGpsX"),
                data.getDouble("BvpBombGpsY"), data.getDouble("BvpBombGpsZ")) else null
            "TV" -> if (data.hasUUID("BvpBombTarget")) {
                val entity = (level() as? ServerLevel)?.getEntity(data.getUUID("BvpBombTarget"))
                if (entity != null && (!entity.isAlive || entity.isRemoved ||
                        (entity as? com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)?.isWreck == true)) {
                    data.remove("BvpBombTarget"); null
                } else entity?.boundingBox?.center
            } else null
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

    override fun onHit(result: HitResult) {
        if (AircraftBombPenetrator.isFuzing(this)) return
        super.onHit(result)
    }

    override fun onHitEntity(result: EntityHitResult) {
        val entity = result.entity
        val owner = this.owner
        if (entity == owner || (owner != null && entity == owner.vehicle) || entity is AerialBombEntity) return
        if (AircraftClusterBomb.isTypedChild(this) || AircraftClusterBomb.consumeSensorImpact(this)) {
            detonateTypedChild(result.location); discard(); return
        }
        if (AircraftClusterBomb.release(this, result.location)) return
        AircraftMunitionDebug.log(this, "bomb entity impact")
        super.onHitEntity(result)
        if (this.level() is ServerLevel) {
            if (extraCraterEnabled()) {
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
        if (AircraftClusterBomb.isTypedChild(this) || AircraftClusterBomb.consumeSensorImpact(this)) {
            detonateTypedChild(blockHitResult.location); discard(); return
        }
        if (AircraftClusterBomb.release(this, blockHitResult.location)) return
        if (AircraftBombPenetrator.begin(this, blockHitResult)) return
        AircraftMunitionDebug.log(this, "bomb block impact")
        super.onHitBlock(blockHitResult)
        if (this.level() is ServerLevel) {
            if (extraCraterEnabled()) {
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

    override fun causeExplode(vec3: Vec3) {
        if (AircraftClusterBomb.isTypedChild(this) || AircraftClusterBomb.consumeSensorImpact(this)) {
            detonateTypedChild(vec3); discard(); return
        }
        if (!AircraftClusterBomb.release(this, vec3)) super.causeExplode(vec3)
    }

    /** Penetrator fuzes push the TNT fireball forward along the bomb's travel direction. */
    override fun buildExplosion(vec3: Vec3): com.atsuishio.superbwarfare.tools.CustomExplosion.Builder =
        super.buildExplosion(vec3).penetrator(AircraftBombPenetrator.direction(this))

    /**
     * Typed children (HEAT bomblets, sensor skeets) leave direct hits to their armor profile and never used the
     * legacy blast. A TNT-equivalent bomblet charge still detonates once where it lands. The (inert) sensor
     * dispenser carries no charge, so this is a no-op for it.
     */
    private fun detonateTypedChild(at: Vec3) {
        if (exploded || level() !is ServerLevel || !TntBlast.active(this)) return
        exploded = true
        buildExplosion(at).explode()
    }

    /** The legacy 3-block crater is replaced by the fireball's own block rule for TNT-equivalent bombs. */
    private fun extraCraterEnabled(): Boolean = ExplosionConfig.EXPLOSION_DESTROY.get() &&
        ExplosionConfig.EXTRA_EXPLOSION_EFFECT.get() && !TntBlast.active(this)
}
