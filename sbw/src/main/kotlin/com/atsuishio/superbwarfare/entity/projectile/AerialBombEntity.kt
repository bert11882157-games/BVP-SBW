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

    /**
     * Horizontal drag multiplier of this bomb's unguided flight, or null for a bomb without one (legacy SBW bombs).
     * The client gets it with the spawn data so it flies the same step as the server (owner 2026-09-30: munitions
     * stuttered; bombs snapped to every tracker position).
     */
    private var clientDrag: Double? = null

    private fun bombDrag(): Double? = if (level().isClientSide) clientDrag
        else if (persistentData.contains("BvpBombMode")) persistentData.getDouble("BvpBombDrag") else null

    override fun smoothsBallisticFlight(): Boolean = motionSyncMode() != MotionSyncMode.NONE

    override fun ballisticStep(velocity: Vec3): Vec3 {
        val base = super.ballisticStep(velocity)
        val drag = bombDrag() ?: return base
        return AircraftBombFlight.applyHorizontalDrag(base, drag)
    }

    // Publish this tick's state after the drag and guidance below, not before (the client got a stale velocity).
    override fun deferTickSynchronization(): Boolean = true

    override fun writeSpawnData(buffer: net.minecraft.network.FriendlyByteBuf) {
        super.writeSpawnData(buffer)
        buffer.writeDouble(bombDrag() ?: -1.0)
    }

    override fun readSpawnData(additionalData: net.minecraft.network.FriendlyByteBuf) {
        super.readSpawnData(additionalData)
        val drag = additionalData.readDouble()
        clientDrag = if (drag.isFinite() && drag >= 0.0) drag else null
    }

    override fun tick() {
        try {
            flightTick()
        } finally {
            commitTickSynchronization()
        }
    }

    private fun flightTick() {
        super.tick()
        if (isRemoved) return
        if (level().isClientSide) {
            // the same unguided step the server flies; guided steering arrives as corrections
            bombDrag()?.let { deltaMovement = AircraftBombFlight.applyHorizontalDrag(deltaMovement, it) }
            return
        }
        if (AircraftBombPenetrator.tick(this)) return
        if (AircraftClusterBomb.tick(this)) return
        val data = persistentData
        if (!data.contains("BvpBombMode")) return
        val mode = data.getString("BvpBombMode")
        val guided = mode != "DUMB"
        var velocity = deltaMovement
        if (!guided) velocity = AircraftBombFlight.applyHorizontalDrag(velocity, data.getDouble("BvpBombDrag"))
        val target = when (mode) {
            "LASER" -> (level() as? ServerLevel)?.let { server ->
                // live: a spot painted on a vehicle moves with it
                if (data.hasUUID("BvpBombAircraft")) AircraftDesignationData.get(server)
                    .get(data.getUUID("BvpBombAircraft"))?.live(server) else null
            }
            "GPS" -> if (data.contains("BvpBombGpsX")) Vec3(data.getDouble("BvpBombGpsX"),
                data.getDouble("BvpBombGpsY"), data.getDouble("BvpBombGpsZ")) else null
            "TV" -> if (com.atsuishio.superbwarfare.api.aircraft.AircraftTvGuidance.isTv(this)) {
                com.atsuishio.superbwarfare.api.aircraft.AircraftTvGuidance.aimPoint(this)
            } else if (data.hasUUID("BvpBombTarget")) {
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
        if (!guided) {
            deltaMovement = velocity
            return
        }
        // Guided bombs glide (owner 2026-09-30: "glide bombs retain their energy like crazy, I should hardly ever be
        // able to climb"): lift is limited by speed, and parasite plus induced drag bleed energy on every pull.
        val start = velocity.add(0.0, gravity.toDouble(), 0.0)
        val maxTurn = Math.toRadians(data.getDouble("BvpBombTurn").coerceIn(0.0, 15.0))
        if (mode == "TV") {
            // TV glide bombs (owner 2026-10-02: "tv guided bombs should at least try to glide"): toward the crosshair
            // on the glide slope, straight down the line once it is reachable, never above 400 km/h for long.
            val glide = AircraftBombFlight.Glide.of(data, AircraftBombFlight.Glide.TV_DEFAULT)
            val desired = AircraftBombFlight.tvGlideDirection(position(), start, target, glide)
            // no more agile than the laser-kit bomb of its class at this speed (owner 2026-10-02: "turn WAY slower")
            val turn = AircraftBombFlight.tvMaxTurn(start.length(), gravity.toDouble(), maxTurn)
            deltaMovement = AircraftBombFlight.limitTvSpeed(
                AircraftBombFlight.glideStep(start, gravity.toDouble(), desired, turn, glide), start.length())
            return
        }
        // laser/GPS: straight at the target once it is within the glide, a shallow lofted approach before that
        val glide = AircraftBombFlight.Glide.of(data)
        val desired = target?.let { AircraftBombFlight.guidedDirection(position(), it, glide) }
        deltaMovement = AircraftBombFlight.glideStep(start, gravity.toDouble(), desired, maxTurn, glide)
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
        // A dispenser that strikes something before opening (a vehicle whose armor would consume the hit) still
        // scatters its load there instead of vanishing.
        if (!level().isClientSide && result is EntityHitResult && AircraftClusterBomb.configured(this) &&
            !AircraftClusterBomb.isTypedChild(this)) {
            val entity = result.entity
            val owner = this.owner
            if (entity != owner && (owner == null || entity != owner.vehicle) && entity !is AerialBombEntity &&
                AircraftClusterBomb.release(this, result.location)) return
        }
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
    private fun extraCraterEnabled(): Boolean = ExplosionConfig.extraBlockEffects() && !TntBlast.active(this)
}
