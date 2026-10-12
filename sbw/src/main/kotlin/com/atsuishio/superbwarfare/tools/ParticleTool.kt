package com.atsuishio.superbwarfare.tools

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.Mod.Companion.queueServerWork
import com.atsuishio.superbwarfare.client.particle.CannonMuzzleFlareOption
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.init.ModParticleTypes
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage.Companion.sendToNearbyPlayers
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

object ParticleTool {
    private val explosionFxHandlers = linkedMapOf<ResourceLocation, ExplosionFxHandler>()

    /** Registers or replaces a namespaced source-aware explosion presentation handler. */
    @JvmStatic
    fun registerExplosionFxHandler(id: ResourceLocation, handler: ExplosionFxHandler) {
        synchronized(explosionFxHandlers) {
            explosionFxHandlers[id] = handler
        }
    }

    /** Removes a source-aware explosion presentation handler. */
    @JvmStatic
    fun unregisterExplosionFxHandler(id: ResourceLocation): Boolean {
        return synchronized(explosionFxHandlers) {
            explosionFxHandlers.remove(id) != null
        }
    }

    @JvmStatic
    fun <T : ParticleOptions> sendParticle(
        level: ServerLevel, particle: T, x: Double, y: Double, z: Double, count: Int,
        xOffset: Double, yOffset: Double, zOffset: Double, speed: Double, force: Boolean
    ) {
        val players = level.players()
        NetworkTelemetry.recordSystemWork("fx.particle_fanout", items = count, recipients = players.size)
        for (serverPlayer in players) {
            sendParticle(level, particle, x, y, z, count, xOffset, yOffset, zOffset, speed, force, serverPlayer)
        }
    }

    @JvmStatic
    fun <T : ParticleOptions> sendParticle(
        level: ServerLevel, particle: T, x: Double, y: Double, z: Double, count: Int,
        xOffset: Double, yOffset: Double, zOffset: Double, speed: Double, force: Boolean, viewer: ServerPlayer
    ) {
        if (!VehicleDeathEffects.allowsFullFx(viewer.uuid)) return
        // Retired effect: do not spend packets on it (clients create nothing for it either).
        if (particle.type === ModParticleTypes.FIRE_STAR.get()) return
        level.sendParticles(viewer, particle, force, x, y, z, count, xOffset, yOffset, zOffset, speed)
    }

    @JvmStatic
    fun spawnExplosionParticles(type: ParticleType?, level: Level, pos: Vec3) {
        dispatchExplosionFx(
            level,
            ExplosionFxContext(
                directSource = null,
                attacker = null,
                gameplayPosition = pos,
                particlePosition = pos,
                radius = 0f,
                emitFx = true,
                particleType = type ?: ParticleType.MINI,
                causeId = null,
                profileId = null
            )
        )
    }

    @JvmStatic
    fun dispatchExplosionFx(level: Level, context: ExplosionFxContext) {
        if (!context.emitFx) return
        val source = context.directSource as? VehicleEntity
        val excluded = if (level is ServerLevel && source != null && (source.health <= 0 || source.isWreck))
            VehicleDeathEffects.exclusionsFor(source) else null
        VehicleDeathEffects.withRetainedAircraft(source) {
            VehicleDeathEffects.withExclusions(excluded) { dispatchExplosionFxToAudience(level, context) }
        }
    }

    private fun dispatchExplosionFxToAudience(level: Level, context: ExplosionFxContext) {
        // Ammunition-rack providers explicitly own the mushroom presentation. Ordinary vehicle
        // destruction must not inherit the giant mushroom recipe from its blast radius.
        val vehicle = context.directSource as? VehicleEntity
        val type = if (vehicle != null && (vehicle.health <= 0 || vehicle.isWreck) &&
            context.particleType in setOf(ParticleType.HUGE, ParticleType.GIANT)) ParticleType.LARGE else context.particleType
        // FFA's impact presentation draws the burst but its own cue is a faint, non-positional clip: the blast is
        // still heard through SpatialAudio at its size (r45 audio test: a 118 kg bomb played only FFA's cue at 0.1).
        if (com.atsuishio.superbwarfare.api.effect.MissilePresentation.impact(
                level, context.particlePosition, context.directSource, context.radius)) {
            playExplosionSound(level, context.particlePosition, type)
            return
        }
        if (level is ServerLevel) com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer.rememberEffect(
            level, context.particlePosition, maxOf(8.0, context.radius * 2.0))

        val handlers = synchronized(explosionFxHandlers) {
            explosionFxHandlers.toList()
        }
        for ((id, handler) in handlers) {
            try {
                if (handler.handle(level, context)) return
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Explosion FX handler {} failed for {}", id, context.directSource, exception)
            }
        }

        if (context.radius > 0 && com.atsuishio.superbwarfare.api.effect.MissilePresentation.effect(
                level, context.particlePosition, context.radius, false)) {
            playExplosionSound(level, context.particlePosition, type)
            return
        }

        when (type) {
            ParticleType.MINI -> spawnMiniExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
            ParticleType.SMALL -> spawnSmallExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
            ParticleType.MEDIUM -> spawnMediumExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
            // A TNT blast draws its own fireball, smoke, dust, chunks and mushroom (BlastEffects); the old burst
            // recipes of thousands of engine particles evicted each other when blasts overlapped. Keep their sound.
            ParticleType.LARGE -> spawnLargeExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
            ParticleType.HUGE -> spawnHugeExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
            ParticleType.GIANT -> spawnGiantExplosionParticles(level, context.particlePosition, context.fireballRadius <= 0f)
        }
    }

    private fun playExplosionSoundLayers(
        level: ServerLevel, pos: Vec3, close: SoundEvent, closeVolume: Float, far: SoundEvent, farVolume: Float,
        veryFar: SoundEvent, veryFarVolume: Float, gain: Float = 1f,
    ) {
        // one event: each listener hears the close, far or very-far layer for its distance (SpatialAudio)
        val cue = com.atsuishio.superbwarfare.api.audio.SpatialAudio.weaponCue(
            null, close, far, veryFar, closeVolume, farVolume, veryFarVolume)
        com.atsuishio.superbwarfare.api.audio.SpatialAudio.emit(level, pos, cue, gain, 1f, null, null,
            com.atsuishio.superbwarfare.api.audio.SpatialAudio.Category.EXPLOSION, null)
    }

    /**
     * SpatialAudio gain of each explosion tier: a blast is heard at full level out to 6 x gain^2 blocks. Tank gun fire
     * plays at 2.0 (tools/audio/weapon_loudness.py): a shell burst (MEDIUM) sits a little under it, a bomb (LARGE and
     * up) above it, so bombs and cannon are the loud events of a battle and small bursts stay modest.
     */
    private fun explosionGain(type: ParticleType): Float = when (type) {
        ParticleType.MINI -> 0.7f
        ParticleType.SMALL -> 1.0f
        ParticleType.MEDIUM -> 1.6f
        ParticleType.LARGE -> 2.4f
        ParticleType.HUGE -> 3.0f
        ParticleType.GIANT -> 3.6f
    }

    /**
     * The layered explosion sound of [type] alone, for presentations that draw their own burst (BVP lean impacts)
     * but must still be heard like any other explosion of that size.
     */
    @JvmStatic
    fun playExplosionSound(level: Level?, pos: Vec3, type: ParticleType) {
        if (level !is ServerLevel) return
        when (type) {
            // a small burst (AP impact, 20-30 mm HE): quieter than the gun that fired it, heard to 48 blocks
            ParticleType.MINI -> com.atsuishio.superbwarfare.api.audio.SpatialAudio.emit(level, pos,
                com.atsuishio.superbwarfare.api.audio.SpatialAudio.Cue(null, ModSounds.MINI_EXPLOSION.get(), null, null,
                    16f, 48f, 48f), explosionGain(type), 1f, null, null,
                com.atsuishio.superbwarfare.api.audio.SpatialAudio.Category.EXPLOSION, null)
            ParticleType.SMALL -> playExplosionSoundLayers(level, pos, ModSounds.EXPLOSION_CLOSE.get(), 2f,
                ModSounds.EXPLOSION_FAR.get(), 8f, ModSounds.EXPLOSION_VERY_FAR.get(), 32f, explosionGain(type))
            ParticleType.MEDIUM -> playExplosionSoundLayers(level, pos, ModSounds.EXPLOSION_CLOSE.get(), 4f,
                ModSounds.EXPLOSION_FAR.get(), 16f, ModSounds.EXPLOSION_VERY_FAR.get(), 32f, explosionGain(type))
            ParticleType.LARGE -> playExplosionSoundLayers(level, pos, ModSounds.HUGE_EXPLOSION_CLOSE.get(), 6f,
                ModSounds.HUGE_EXPLOSION_FAR.get(), 20f, ModSounds.HUGE_EXPLOSION_VERY_FAR.get(), 64f, explosionGain(type))
            ParticleType.HUGE -> playExplosionSoundLayers(level, pos, ModSounds.HUGE_EXPLOSION_CLOSE.get(), 8f,
                ModSounds.HUGE_EXPLOSION_FAR.get(), 24f, ModSounds.HUGE_EXPLOSION_VERY_FAR.get(), 128f, explosionGain(type))
            ParticleType.GIANT -> playExplosionSoundLayers(level, pos, ModSounds.HUGE_EXPLOSION_CLOSE.get(), 12f,
                ModSounds.HUGE_EXPLOSION_FAR.get(), 32f, ModSounds.HUGE_EXPLOSION_VERY_FAR.get(), 192f, explosionGain(type))
        }
    }

    //@formatter:off
    @JvmStatic
    @JvmOverloads
    fun spawnMiniExplosionParticles(level: Level, pos: Vec3, burst: Boolean = true) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            playExplosionSound(level, pos, ParticleType.MINI)
            if (!burst) return
            sendParticle(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 2, 0.1, 0.1, 0.1, 0.02, true)
            sendParticle(level, ParticleTypes.EXPLOSION, x, y, z, 2, 0.05, 0.05, 0.05, 1.0, true)
            sendParticle(level, ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.2, 0.2, 0.2, 0.02, true)
            sendParticle(level, ParticleTypes.FLASH, x, y, z, 1, 0.0, 0.0, 0.0, 20.0, true)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun spawnSmallExplosionParticles(level: Level?, pos: Vec3, burst: Boolean = true) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            playExplosionSound(level, pos, ParticleType.SMALL)
            if (!burst) return

            sendParticle(level, ParticleTypes.EXPLOSION, x, y, z, 2, 0.05, 0.05, 0.05, 1.0, true)
            sendParticle(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 3, 0.1, 0.1, 0.1, 0.02, true)
            sendParticle(level, ParticleTypes.LARGE_SMOKE, x, y, z, 4, 0.2, 0.2, 0.2, 0.02, true)
            sendParticle(level, ParticleTypes.FLASH, x, y, z, 3, 0.1, 0.1, 0.1, 20.0, true)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun spawnMediumExplosionParticles(level: Level?, pos: Vec3, burst: Boolean = true) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            if (!burst) {
                playExplosionSound(level, pos, ParticleType.MEDIUM)
                return
            }
            if ((level.getBlockState(BlockPos.containing(x, y, z))).block === Blocks.WATER) {
                sendParticle(level, ParticleTypes.CLOUD, x, y + 3, z, 20, 1.0, 3.0, 1.0, 0.01, true)
                sendParticle(level, ParticleTypes.CLOUD, x, y + 3, z, 30, 2.0, 1.0, 2.0, 0.01, true)
                sendParticle(level, ParticleTypes.FALLING_WATER, x, y + 3, z, 50, 1.5, 4.0, 1.5, 1.0, true)
                sendParticle(level, ParticleTypes.BUBBLE_COLUMN_UP, x, y, z, 60, 3.0, 0.5, 3.0, 0.1, true)
            }

            playExplosionSound(level, pos, ParticleType.MEDIUM)

            sendParticle(level, ParticleTypes.EXPLOSION, x, y + 1, z, 5, 0.7, 0.7, 0.7, 1.0, true)
            sendParticle(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 1, z, 20, 0.2, 1.0, 0.2, 0.02, true)
            sendParticle(level, ParticleTypes.LARGE_SMOKE, x, y + 1, z, 10, 0.4, 1.0, 0.4, 0.02, true)
            sendParticle(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.25, z, 40, 2.0, 0.001, 2.0, 0.01, true)
            sendParticle(level, ParticleTypes.FLASH, x, y + 0.5, z, 50, 0.2, 0.2, 0.2, 20.0, true)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun spawnLargeExplosionParticles(level: Level?, pos: Vec3, burst: Boolean = true) {
        if (level is ServerLevel) {
            if (burst) ExplosionBurstMessage.send(
                level,
                ExplosionBurstMessage.Recipe.LARGE,
                pos,
                level.getBlockState(BlockPos.containing(pos)).block === Blocks.WATER,
            )

            playExplosionSound(level, pos, ParticleType.LARGE)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun spawnHugeExplosionParticles(level: Level?, pos: Vec3, burst: Boolean = true) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            playExplosionSound(level, pos, ParticleType.HUGE)

            if (burst) ExplosionBurstMessage.send(
                level,
                ExplosionBurstMessage.Recipe.HUGE,
                pos,
                level.getBlockState(BlockPos.containing(pos)).block === Blocks.WATER,
            )

            sendToNearbyPlayers(level, x, y, z, 192.0, 30.0, 12.0)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun spawnGiantExplosionParticles(level: Level?, pos: Vec3, burst: Boolean = true) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            playExplosionSound(level, pos, ParticleType.GIANT)

            if (burst) ExplosionBurstMessage.send(
                level,
                ExplosionBurstMessage.Recipe.GIANT,
                pos,
                level.getBlockState(BlockPos.containing(pos)).block === Blocks.WATER,
            )
            sendToNearbyPlayers(level, x, y, z, 384.0, 30.0, 16.0)
        }
    }

    @JvmStatic
    fun spawnBulletHitWaterParticles(level: Level?, pos: Vec3) {
        val x = pos.x
        val y = pos.y
        val z = pos.z

        if (level is ServerLevel) {
            spawnRadialParticles(level,
                CustomCloudOption(1f, 1f, 1f, 20, 0.3f, 0f, cooldown = false, light = false),
                pos, 60, 8.0)
            queueServerWork(8) {
                spawnRadialParticles(level,
                    CustomCloudOption(1f, 1f, 1f, 17, 0.3f, 0f, cooldown = false, light = false),
                    pos, 45, 8.0)
            }
            queueServerWork(14) {
                spawnRadialParticles(level,
                    CustomCloudOption(1f, 1f, 1f, 15, 0.3f, 0f, cooldown = false, light = false),
                    pos, 30, 4.0)
            }
            sendParticle(level, ParticleTypes.CLOUD, x, y - 0.2, z, 3, 0.2, 0.0, 0.2, 0.002, false)
            sendParticle(level, ParticleTypes.BUBBLE_COLUMN_UP, x, y - 0.5, z, 5, 0.4, 0.2, 0.4, 0.005, false)
        }
    }

    @JvmStatic
    fun cannonHitParticles(serverLevel: ServerLevel, pos: Vec3) {
        sendParticle(serverLevel, ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 2, 0.5, 0.5, 0.5, 1.0, true)
        sendParticle(serverLevel, ParticleTypes.FLASH, pos.x, pos.y, pos.z, 2, 0.2, 0.2, 0.2, 10.0, true)
    }

    @JvmStatic
    fun spawnMediumCannonMuzzleParticles(direct: Vec3, pos: Vec3, serverLevel: ServerLevel, entity: Entity?) {
        ParticleTool.spawnDirectionalParticles(4, 0.1, serverLevel, CannonMuzzleFlareOption(1f, 1f, 1f, 8, 0.7f, 1, 0.2f), direct, pos, 0.3)
        ParticleTool.spawnDirectionalParticles(3, 0.06, serverLevel, CannonMuzzleFlareOption(1f, 1f, 1f, 8, 0.72f, 1, 0.15f), direct, pos, 0.2)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.4f, 0.4f, 0.4f, 45, 0.88f, 2, 0.05f), direct, pos, 0.15)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.45f, 0.45f, 0.45f, 47, 0.90f, 2, 0.03f), direct, pos, 0.125)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.5f, 0.5f, 0.5f, 48, 0.92f, 2, 0.01f), direct, pos, 0.1)
    }

    @JvmStatic
    fun spawnBigCannonMuzzleParticles(direct: Vec3, pos: Vec3, serverLevel: ServerLevel, entity: Entity?) {
        ParticleTool.spawnDirectionalParticles(10, 0.1, serverLevel, CannonMuzzleFlareOption(1f, 1f, 1f, 8, 0.7f, 1, 2.2f), direct, pos, 1.0)
        ParticleTool.spawnDirectionalParticles(8, 0.06, serverLevel, CannonMuzzleFlareOption(1f, 1f, 1f, 8, 0.72f, 1, 1.5f), direct, pos, 0.8)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.4f, 0.4f, 0.4f, 36, 0.84f, 2, 1.1f), direct, pos, 0.6)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.4f, 0.4f, 0.4f, 39, 0.87f, 2, 0.9f), direct, pos, 0.5)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.4f, 0.4f, 0.4f, 42, 0.87f, 2, 0.7f), direct, pos, 0.35)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.4f, 0.4f, 0.4f, 45, 0.88f, 2, 0.5f), direct, pos, 0.25)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.45f, 0.45f, 0.45f, 47, 0.90f, 2, 0.3f), direct, pos, 0.17)
        ParticleTool.spawnDirectionalParticles(1, 0.0, serverLevel, CannonMuzzleFlareOption(0.5f, 0.5f, 0.5f, 48, 0.92f, 2, 0.1f), direct, pos, 0.1)
    }

    @JvmStatic
    fun spawnDirectionalParticles(
        count: Int,
        radius: Double,
        level: ServerLevel,
        particle: ParticleOptions,
        direct: Vec3,
        pos: Vec3?,
        speed: Double
    ) {
        if (pos == null) return

        val direction = direct.normalize()
        val random = getRandomPerpendicular(direction)
        val u = random.normalize()
        val v = direction.cross(u).normalize()

        spawnCircularParticles(level, pos, u, v, count, radius, particle, direct, speed)
    }

    @JvmStatic
    fun spawnDirectionalParticles(
        count: Int,
        radius: Double,
        level: Level,
        particle: ParticleOptions,
        direct: Vec3,
        pos: Vec3?,
        speed: Double
    ) {
        if (pos == null) return

        val direction = direct.normalize()
        val random = getRandomPerpendicular(direction)
        val u = random.normalize()
        val v = direction.cross(u).normalize()

        spawnCircularParticles(level, pos, u, v, count, radius, particle, direct, speed)
    }

    private fun spawnRadialParticles(level: ServerLevel, particle: ParticleOptions,
                                     center: Vec3, count: Int, speed: Double) {
        val phase = Math.random() * Math.PI * 2
        repeat(count) { index ->
            val direction = Vec3(1.0, 0.0, 0.0)
                .yRot((phase + 2 * Math.PI * index / count).toFloat())
            sendParticle(level, particle, center.x, center.y, center.z,
                0, direction.x, direction.y, direction.z, speed, true)
        }
    }

    private fun getRandomPerpendicular(dir: Vec3): Vec3 {
        val candidate1 = Vec3(dir.y, -dir.x, 0.0) // Perpendicular in the XY plane.
        if (candidate1.lengthSqr() > 1e-4) return candidate1
        return Vec3(0.0, dir.z, -dir.y) // Fallback for a near-vertical direction.
    }

    private fun spawnCircularParticles(
        level: ServerLevel,
        center: Vec3,
        u: Vec3,
        v: Vec3,
        count: Int,
        radius: Double,
        particle: ParticleOptions,
        direct: Vec3,
        speed: Double
    ) {
        for (i in 0..<count) {
            val theta = 2 * Math.PI * i / count
            val xOffset = radius * (cos(theta) * u.x + sin(theta) * v.x)
            val yOffset = radius * (cos(theta) * u.y + sin(theta) * v.y)
            val zOffset = radius * (cos(theta) * u.z + sin(theta) * v.z)

            val pos = center.add(xOffset, yOffset, zOffset)
            spawnParticle(level, pos, particle, direct, center, speed)
        }
    }

    private fun spawnCircularParticles(
        level: Level,
        center: Vec3,
        u: Vec3,
        v: Vec3,
        count: Int,
        radius: Double,
        particle: ParticleOptions,
        direct: Vec3,
        speed: Double
    ) {
        for (i in 0..<count) {
            val theta = 2 * Math.PI * i / count
            val xOffset = radius * (cos(theta) * u.x + sin(theta) * v.x)
            val yOffset = radius * (cos(theta) * u.y + sin(theta) * v.y)
            val zOffset = radius * (cos(theta) * u.z + sin(theta) * v.z)

            val pos = center.add(xOffset, yOffset, zOffset)
            spawnParticle(level, pos, particle, direct, center, speed)
        }
    }

    private fun spawnParticle(
        level: ServerLevel,
        pos: Vec3,
        particle: ParticleOptions,
        direct: Vec3,
        originPos: Vec3,
        speed: Double
    ) {
        val v0 = originPos.vectorTo(pos).normalize().add(direct.scale(6.0))
        sendParticle(
            level, particle, pos.x, pos.y, pos.z,
            0, v0.x, v0.y, v0.z, speed, true
        )
    }

    private fun spawnParticle(
        level: Level,
        pos: Vec3,
        particle: ParticleOptions,
        direct: Vec3,
        originPos: Vec3,
        speed: Double
    ) {
        val v0 = originPos.vectorTo(pos).normalize().add(direct.scale(6.0))
        sendParticle(level, particle, pos.x, pos.y, pos.z, v0.x, v0.y, v0.z, speed)
    }

    @JvmStatic
    fun sendParticle(
        level: Level,
        particle: ParticleOptions,
        x: Double,
        y: Double,
        z: Double,
        xOffset: Double,
        yOffset: Double,
        zOffset: Double,
        speed: Double
    ) {
        val vec3 = Vec3(xOffset, yOffset, zOffset).normalize().scale(speed * (0.75 + Math.random() * 0.5))
        level.addParticle(particle, x, y, z, vec3.x, vec3.y, vec3.z)
    }

    @JvmStatic
    fun spawnBarrelSmoke(count: Int, level: ServerLevel, v0: Vec3, pos: Vec3) {
        repeat(count) {
            sendParticle(
                level, ModParticleTypes.RISING_SMOKE.get(), pos.x, pos.y, pos.z,
                0, v0.x, v0.y, v0.z, 0.22, true
            )
        }
    }
    //@formatter:on

    @Serializable
    enum class ParticleType {
        @SerializedName("Mini")
        @SerialName("Mini")
        MINI,

        @SerializedName("Small")
        @SerialName("Small")
        SMALL,

        @SerializedName("Medium")
        @SerialName("Medium")
        MEDIUM,

        @SerializedName("Large")
        @SerialName("Large")
        LARGE,

        @SerializedName("Huge")
        @SerialName("Huge")
        HUGE,

        @SerializedName("Giant")
        @SerialName("Giant")
        GIANT,
    }
}
