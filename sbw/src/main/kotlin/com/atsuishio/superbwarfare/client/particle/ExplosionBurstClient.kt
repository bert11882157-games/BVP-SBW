package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod.Companion.queueClientWork
import com.atsuishio.superbwarfare.init.ModParticleTypes
import com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage
import com.atsuishio.superbwarfare.tools.clientLevel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.phys.Vec3
import java.util.Random
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

object ExplosionBurstClient {
    @JvmStatic
    fun spawn(message: ExplosionBurstMessage) {
        val level = clientLevel ?: return
        val position = message.position
        if (message.underwater) spawnHeavyUnderwaterBurst(level, position)

        val phaseRandom = Random(message.seed)
        when (message.recipe) {
            ExplosionBurstMessage.Recipe.LARGE -> spawnLarge(level, position, phaseRandom)
            ExplosionBurstMessage.Recipe.HUGE -> spawnHuge(level, position, phaseRandom)
            ExplosionBurstMessage.Recipe.GIANT -> spawnGiant(level, position, phaseRandom)
        }
    }

    private fun spawnLarge(level: ClientLevel, pos: Vec3, phaseRandom: Random) {
        spawnBatch(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.add(0.0, 1.0, 0.0), 60, 0.5, 2.0, 0.5, 0.02)
        spawnBatch(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.add(0.0, 0.25, 0.0), 120, 5.0, 0.001, 5.0, 0.01)
        spawnBatch(level, ModParticleTypes.FIRE_STAR.get(), pos.add(0.0, 0.2, 0.0), 100, 0.0, 0.0, 0.0, 1.2)
        spawnBatch(level, ParticleTypes.EXPLOSION, pos.add(0.0, 1.0, 0.0), 35, 1.5, 1.5, 1.5, 1.0)
        spawnBatch(level, ParticleTypes.FLASH, pos.add(0.0, 1.0, 0.0), 120, 3.0, 3.0, 3.0, 20.0)
        repeat(2) { ring ->
            spawnRadial(level, CustomCloudOption(0xFFFFFF, 25, 2f, 0f, false, false),
                pos.add(0.0, 0.2, 0.0), 150, (140 - 2 * ring).toDouble(), phaseRandom)
        }
    }

    private fun spawnHuge(level: ClientLevel, pos: Vec3, phaseRandom: Random) {
        repeat(4) { ring ->
            spawnRadial(level, CustomCloudOption(0xFFFFFF, 25, 4f, 0f, false, false),
                pos.add(0.0, 0.5, 0.0), 200, (200 - 2 * ring).toDouble(), phaseRandom)
        }
        spawnBatch(level, ParticleTypes.EXPLOSION, pos.add(0.0, 3.0, 0.0), 75, 2.5, 2.5, 2.5, 1.0)
        spawnBatch(level, ParticleTypes.FLASH, pos.add(0.0, 3.0, 0.0), 200, 5.0, 5.0, 5.0, 20.0)
        spawnBatch(level, ModParticleTypes.FIRE_STAR.get(), pos.add(0.0, 1.0, 0.0), 400, 0.0, 0.0, 0.0, 1.5)
        spawnBatch(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.add(0.0, 3.0, 0.0), 75, 2.0, 3.0, 2.0, 0.005)
        spawnBatch(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos, 150, 7.0, 0.1, 7.0, 0.005)
        spawnBatch(level, ParticleTypes.CLOUD, pos.add(0.0, 1.0, 0.0), 200, 3.0, 4.0, 3.0, 0.4)
    }

    private fun spawnGiant(level: ClientLevel, pos: Vec3, phaseRandom: Random) {
        spawnBatch(level, ParticleTypes.EXPLOSION, pos.add(0.0, 6.0, 0.0), 100, 6.0, 6.0, 6.0, 1.0)
        spawnBatch(level, ParticleTypes.FLASH, pos.add(0.0, 7.0, 0.0), 200, 7.0, 7.0, 7.0, 1.0)
        spawnBatch(level, ModParticleTypes.FIRE_STAR.get(), pos.add(0.0, 3.0, 0.0), 800, 0.0, 0.0, 0.0, 2.0)
        repeat(5) { ring ->
            spawnRadial(level, CustomCloudOption(1f, 1f, 1f, 25, 4f, 0f, false, false),
                pos.add(0.0, 1.0, 0.0), 200, (500 - 3 * ring).toDouble(), phaseRandom)
        }

        repeat(24) { stage ->
            queueClientWork(stage) {
                if (clientLevel !== level) return@queueClientWork
                spawnGiantStage(level, pos, stage)
            }
        }
    }

    private fun spawnGiantStage(level: ClientLevel, pos: Vec3, stage: Int) {
        if (stage < 12) {
            spawnBatch(level, CustomCloudOption(1 - stage / 24f, 0.5f - stage / 48f, 0f, 100, 4f, 0f, true, true),
                pos.add(0.0, 2.0 * stage, 0.0), 35, 2 - 0.1 * stage, 0.5, 2 - 0.1 * stage, 0.005)
            spawnBatch(level, CustomCloudOption(0.8f - stage / 24f, 0.4f - stage / 48f, 0f, 100, 4f, 0f, true, true),
                pos.add(0.0, 0.5, 0.0), 55, 3 + 0.5 * stage, 0.3, 3 + 0.5 * stage, 0.005)
        }
        if (stage in 8..<16) {
            val k = stage - 8
            val center = pos.add(0.0, 20.0, 0.0)
            spawnBatch(level, CustomCloudOption(1f, 0.5f, 0f, 100, 6f, 0f, true, true),
                center, 20 * k, 1 + 0.5 * k, 1 + 0.2 * k, 1 + 0.5 * k, 0.005)
            spawnBatch(level, CustomCloudOption(0.5f, 0.25f, 0f, 100, 6f, 0f, true, true),
                center, 10 * k, 1 + 0.5 * k, 1 + 0.2 * k, 1 + 0.5 * k, 0.005)
            spawnBatch(level, CustomCloudOption(0.25f, 0.125f, 0f, 100, 8f, 0f, true, true),
                center, 10 * k, 1 + 0.5 * k, 1 + 0.2 * k, 1 + 0.5 * k, 0.005)
        }
        spawnBatch(level, CustomCloudOption(0.667f, 0.631f, 0.592f, 100, 4f, 0f, false, false),
            pos.add(0.0, 0.2, 0.0), 4 * stage, stage.toDouble(), 0.1, stage.toDouble(), 0.0003 * stage)
    }

    private fun spawnHeavyUnderwaterBurst(level: ClientLevel, pos: Vec3) {
        spawnBatch(level, ParticleTypes.CLOUD, pos.add(0.0, 3.0, 0.0), 100, 2.0, 6.0, 2.0, 0.01)
        spawnBatch(level, ParticleTypes.CLOUD, pos.add(0.0, 3.0, 0.0), 200, 4.0, 2.0, 4.0, 0.01)
        spawnBatch(level, ParticleTypes.FALLING_WATER, pos.add(0.0, 3.0, 0.0), 500, 3.0, 8.0, 3.0, 1.0)
        spawnBatch(level, ParticleTypes.BUBBLE_COLUMN_UP, pos, 350, 6.0, 1.0, 6.0, 0.1)
    }

    /** Mirrors ClientPacketListener's vanilla particle-packet expansion. */
    private fun spawnBatch(level: ClientLevel, particle: ParticleOptions, center: Vec3, count: Int,
                           xOffset: Double, yOffset: Double, zOffset: Double, speed: Double) {
        if (count == 0) {
            level.addParticle(particle, true, center.x, center.y, center.z,
                xOffset * speed, yOffset * speed, zOffset * speed)
            return
        }
        val random = ThreadLocalRandom.current()
        repeat(count) {
            level.addParticle(
                particle,
                true,
                center.x + random.nextGaussian() * xOffset,
                center.y + random.nextGaussian() * yOffset,
                center.z + random.nextGaussian() * zOffset,
                random.nextGaussian() * speed,
                random.nextGaussian() * speed,
                random.nextGaussian() * speed,
            )
        }
    }

    private fun spawnRadial(level: ClientLevel, particle: ParticleOptions, center: Vec3,
                            count: Int, speed: Double, phaseRandom: Random) {
        val phase = phaseRandom.nextDouble() * Math.PI * 2
        repeat(count) { index ->
            val angle = phase + 2 * Math.PI * index / count
            level.addParticle(particle, true, center.x, center.y, center.z,
                cos(angle) * speed, 0.0, -sin(angle) * speed)
        }
    }
}
