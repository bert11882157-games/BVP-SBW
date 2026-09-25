package com.atsuishio.superbwarfare.config.server

import com.atsuishio.superbwarfare.config.buildServerConfig
import com.atsuishio.superbwarfare.tools.blast.BlastParameters
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.config.ModConfigEvent

/**
 * Hopkinson-Cranz TNT-equivalent blast model (R = k * W^(1/3), 1 block = 1 m). Munitions without a
 * TNT equivalent keep the legacy ExplosionDamage/ExplosionRadius blast.
 */
object BlastConfig {

    @JvmField
    val ENABLED = buildServerConfig {
        push("tnt_blast")

        comment("Use the TNT-equivalent blast model for munitions that carry a TntEquivalentKg value")
        comment("为带有TNT当量的弹药使用TNT当量爆炸模型")
        define("enabled", true)
    }

    @JvmField
    val FIREBALL_K = buildServerConfig {
        comment("Fireball radius factor k: R = k * W^(1/3) metres. Also the block-destruction radius and the visible fireball")
        defineInRange("fireball_k", 0.5, 0.01, 10.0)
    }

    @JvmField
    val SEVERE_K = buildServerConfig {
        comment("Severe collapse radius factor k. This zone is the infantry and soft-vehicle damage zone")
        defineInRange("severe_k", 1.8, 0.01, 20.0)
    }

    @JvmField
    val MODERATE_K = buildServerConfig {
        comment("Moderate damage radius factor k. Only the visible shockwave travels this far")
        defineInRange("moderate_k", 3.5, 0.01, 40.0)
    }

    @JvmField
    val VEHICLE_RADIUS_FACTOR = buildServerConfig {
        comment("Vehicles within this multiple of the fireball radius take true damage")
        defineInRange("vehicle_true_damage_radius_factor", 1.4, 0.0, 10.0)
    }

    @JvmField
    val VEHICLE_DAMAGE_PER_KG = buildServerConfig {
        comment("True damage (bypasses armor and damage modifiers) per kg of TNT equivalent")
        defineInRange("vehicle_true_damage_per_kg", 3.0, 0.0, 1000.0)
    }

    @JvmField
    val VEHICLE_MIN_KG = buildServerConfig {
        comment("Smallest charge (kg) that deals area damage to vehicles. Below it, vehicles with armor hitboxes take no area blast damage")
        defineInRange("vehicle_true_damage_min_kg", 25.0, 0.0, 100000.0)
    }

    @JvmField
    val SHOCKWAVE_MIN_KG = buildServerConfig {
        comment("Smallest charge (kg) that produces a visible shockwave out to the moderate damage radius")
        defineInRange("shockwave_min_kg", 1000.0, 0.0, 1000000.0)
    }

    @JvmField
    val INFANTRY_CENTRE_DAMAGE = buildServerConfig {
        comment("Damage to an exposed infantry target inside the fireball; falls off to 0 at the severe radius")
        defineInRange("infantry_centre_damage", 40.0, 0.0, 100000.0)
    }

    @JvmField
    val EXPOSURE_FLOOR = buildServerConfig {
        comment("Fraction of blast damage that still reaches a target behind complete cover (0..1)")
        defineInRange("cover_exposure_floor", 0.1, 0.0, 1.0)
    }

    @JvmField
    val SOFT_VEHICLE_CENTRE_DAMAGE = buildServerConfig {
        comment("Centre damage for vehicles without armor hitboxes (aircraft, helicopters, trucks), infantry-style falloff")
        defineInRange("soft_vehicle_centre_damage", 40.0, 0.0, 100000.0)
    }

    @JvmField
    val PENETRATOR_RADIUS_FACTOR = buildServerConfig {
        comment("Penetrator bombs push the fireball volume forward as a cylinder of this radius (fraction of the fireball radius)")
        defineInRange("penetrator_cylinder_radius_factor", 0.5, 0.05, 1.0)
    }

    @JvmField
    val BLOCK_FORCE_PER_CBRT_KG = buildServerConfig {
        comment("Block-breaking force at the centre per cube-root kg, compared with block hardness (metal x3)")
        defineInRange("block_force_per_cbrt_kg", 8.0, 0.0, 1000.0)
    }

    @JvmField
    val SHOCKWAVE_DURATION_TICKS = buildServerConfig {
        comment("Ticks the visible shockwave takes to reach the moderate damage radius")
        defineInRange("shockwave_duration_ticks", 20, 4, 60)
    }

    @JvmField
    val SHOCKWAVE_MAX_PARTICLES = buildServerConfig {
        comment("Maximum live particles of one shockwave")
        defineInRange("shockwave_max_particles", 1500, 0, 4000)
    }

    @JvmField
    val EXTERNAL_MUNITION_BLASTS = buildServerConfig {
        comment("Apply the TNT model to externally simulated aircraft missiles (FFA ballistics) that carry a TNT equivalent")
        define("external_munition_blasts", true).also { pop() }
    }

    private fun double(value: ForgeConfigSpec.DoubleValue, fallback: Double): Double =
        runCatching { value.get() }.getOrDefault(fallback).takeIf { it.isFinite() } ?: fallback

    /** False before the server config is loaded. */
    @JvmStatic
    fun enabled(): Boolean = runCatching { ENABLED.get() }.getOrDefault(false)

    @JvmStatic
    fun externalMunitionBlasts(): Boolean = runCatching { EXTERNAL_MUNITION_BLASTS.get() }.getOrDefault(false)

    @JvmStatic
    fun shockwaveDurationTicks(): Int = runCatching { SHOCKWAVE_DURATION_TICKS.get() }.getOrDefault(20)

    @JvmStatic
    fun shockwaveMaxParticles(): Int = runCatching { SHOCKWAVE_MAX_PARTICLES.get() }.getOrDefault(1500)

    @Volatile
    private var cachedParameters: BlastParameters? = null

    /** Drops the cached parameters after a server config load or reload. */
    @JvmStatic
    fun invalidate() {
        cachedParameters = null
    }

    /** Current model parameters; an inconsistent config (e.g. fireball_k > severe_k) falls back to defaults. */
    @JvmStatic
    fun parameters(): BlastParameters = cachedParameters ?: readParameters().also {
        // Only cache values read from a loaded config; before loading every call re-reads (and gets defaults).
        if (runCatching { ENABLED.get() }.isSuccess) cachedParameters = it
    }

    private fun readParameters(): BlastParameters {
        val d = BlastParameters.DEFAULT
        return runCatching {
            BlastParameters(
                fireballK = double(FIREBALL_K, d.fireballK),
                severeK = double(SEVERE_K, d.severeK),
                moderateK = double(MODERATE_K, d.moderateK),
                vehicleRadiusFactor = double(VEHICLE_RADIUS_FACTOR, d.vehicleRadiusFactor).coerceAtLeast(1e-6),
                vehicleDamagePerKg = double(VEHICLE_DAMAGE_PER_KG, d.vehicleDamagePerKg),
                vehicleMinKg = double(VEHICLE_MIN_KG, d.vehicleMinKg),
                shockwaveMinKg = double(SHOCKWAVE_MIN_KG, d.shockwaveMinKg),
                infantryCentreDamage = double(INFANTRY_CENTRE_DAMAGE, d.infantryCentreDamage),
                exposureFloor = double(EXPOSURE_FLOOR, d.exposureFloor),
                softVehicleCentreDamage = double(SOFT_VEHICLE_CENTRE_DAMAGE, d.softVehicleCentreDamage),
                penetratorRadiusFactor = double(PENETRATOR_RADIUS_FACTOR, d.penetratorRadiusFactor),
                blockForcePerCbrtKg = double(BLOCK_FORCE_PER_CBRT_KG, d.blockForcePerCbrtKg),
            )
        }.getOrDefault(d)
    }
}

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
object BlastConfigEvents {
    @SubscribeEvent
    fun onConfigLoaded(event: ModConfigEvent.Loading) {
        BlastConfig.invalidate()
    }

    @SubscribeEvent
    fun onConfigReloaded(event: ModConfigEvent.Reloading) {
        BlastConfig.invalidate()
    }
}
