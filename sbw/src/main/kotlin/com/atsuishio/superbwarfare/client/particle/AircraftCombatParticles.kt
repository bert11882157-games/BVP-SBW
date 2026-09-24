package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.client.FarEffectsClient
import com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries
import java.util.Random

/** Small bounded recipes, using the pack's transparent orange explosion and TaP flame sprites. */
object AircraftCombatParticles {
    private val diameterMethods = HashMap<Class<*>, java.lang.reflect.Method?>()
    private fun packParticle(name: String): SimpleParticleType? =
        ForgeRegistries.PARTICLE_TYPES.getValue(ResourceLocation("berts_vehicle_pack", name)) as? SimpleParticleType

    fun emit(options: ParticleOptions, point: Vec3, motion: Vec3 = Vec3.ZERO, diameter: Float? = null) {
        val particle = Minecraft.getInstance().particleEngine.createParticle(options,
            point.x, point.y, point.z, motion.x, motion.y, motion.z) ?: return
        if (diameter != null) {
            val method = diameterMethods.getOrPut(particle.javaClass) {
                try { particle.javaClass.getMethod("setDiameter", Float::class.javaPrimitiveType) }
                catch (_: NoSuchMethodException) { null }
            }
            if (method != null) try { method.invoke(particle, diameter) } catch (_: ReflectiveOperationException) { }
        }
        FarEffectsClient.retainParticle(particle)
    }

    fun fire(point: Vec3, diameter: Float, smoke: Boolean) {
        val options: ParticleOptions = packParticle(if (smoke) "wreck_smoke" else "tap_exhaust_flame") ?:
            CustomCloudOption(if (smoke) 0.16f else 1f, if (smoke) 0.14f else 0.35f,
                if (smoke) 0.12f else 0.03f, if (smoke) 35 else 8, diameter, 0f, !smoke, true)
        emit(options, point, Vec3(0.0, if (smoke) 0.045 else 0.015, 0.0), diameter)
    }

    fun grindingSmoke(point: Vec3, speed: Double) {
        val intensity = (speed / .8).coerceIn(0.0, 1.0).toFloat()
        val options: ParticleOptions = packParticle("impact_smoke") ?:
            CustomCloudOption(.4f,.38f,.35f,18,1.2f,0f,false,false)
        emit(options, point, Vec3(0.0, .015, 0.0), .6f + intensity * 1.8f)
    }

    fun wreckSmoke(point: Vec3, diameter: Float) {
        val options: ParticleOptions = packParticle("wreck_smoke") ?:
            CustomCloudOption(.23f,.23f,.23f,64,diameter,0f,false,true)
        emit(options, point, Vec3(0.0, .14, 0.0), diameter)
    }

    /** Reuse the authored transparent afterburner flame for small exposed wreck fires. */
    fun wreckFlame(point: Vec3, strength: Float) {
        if (strength <= .01f) return
        fire(point, 1.1f * strength.coerceIn(0f, 1f), false)
    }

    fun burst(message: ExplosionBurstMessage) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val breakup = message.recipe == ExplosionBurstMessage.Recipe.AIRCRAFT_BREAKUP
        val random = Random(message.seed)
        val radius = if (breakup) 2.8 else 1.3
        val sprite: ParticleOptions = packParticle("explosion") ?:
            CustomCloudOption(1f, 0.4f, 0.04f, 16, 3f, 0f, true, true)
        repeat(if (breakup) 16 else 8) {
            val offset = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(radius * 0.55)
            emit(sprite, message.position.add(offset))
            fire(message.position.add(offset), if (breakup) 3.6f else 2.0f, false)
        }
        repeat(if (breakup) 20 else 12) {
            val offset = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(radius)
            fire(message.position.add(offset), if (breakup) 4.5f else 2.5f, true)
        }
        // One positional sound per event; missile integration suppresses its old emitter and sound.
        level.playLocalSound(message.position.x, message.position.y, message.position.z,
            SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, if (breakup) 4f else 3f,
            if (breakup) 0.75f else 1.3f, false)
    }
}
