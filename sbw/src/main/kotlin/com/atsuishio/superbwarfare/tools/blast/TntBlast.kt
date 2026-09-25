package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.config.server.BlastConfig
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.ForgeConfigSpec

/** Minecraft-side entry points of the TNT-equivalent blast model. Server-side values only. */
object TntBlast {
    /** Data-table keys (munition inventory row ids) of munitions that are not entity-type defaults. */
    const val CM_SUBMUNITION_KEY = "superbwarfare:cannon_shell#cm_submunition"
    const val AIR_BURST_KEY = "superbwarfare:auto_aimable#air_burst"
    const val HE_BULLET_KEY = "superbwarfare:perk/he_bullet"
    const val MICRO_MISSILE_KEY = "superbwarfare:perk/micro_missile"
    const val FIREFLY_KEY = "superbwarfare:perk/firefly"
    const val HAND_GRENADE_KEY = "superbwarfare:hand_grenade"
    const val RGO_GRENADE_KEY = "superbwarfare:rgo_grenade"
    const val C4_KEY = "superbwarfare:c4"
    const val CLAYMORE_KEY = "superbwarfare:claymore"
    const val BLU_43_KEY = "superbwarfare:blu_43"
    const val EDD_KEY = "superbwarfare:edd"
    const val TM_62_KEY = "superbwarfare:tm_62"
    const val LUNGE_MINE_KEY = "superbwarfare:lunge_mine"
    const val PTKM_1R_KEY = "superbwarfare:ptkm_1r"
    const val PTKM_PROJECTILE_KEY = "superbwarfare:ptkm_projectile"

    /** Everything [com.atsuishio.superbwarfare.tools.CustomExplosion] needs to run one TNT blast. */
    data class Plan(
        val kg: Double,
        val parameters: BlastParameters,
        val radii: BlastRadii,
        /** Travel direction of a penetrator bomb; null for a spherical fireball. */
        val penetratorDirection: Vec3?,
    ) {
        val producesShockwave: Boolean get() = BlastModel.producesShockwave(kg, parameters)
    }

    /** True when [kg] selects the TNT model under the current server config. */
    @JvmStatic
    fun active(kg: Double): Boolean = BlastModel.valid(kg) && BlastConfig.enabled()

    @JvmStatic
    fun active(entity: Entity?): Boolean = entity != null && active(TntEquivalents.resolve(entity))

    /** Null keeps the legacy blast (no charge, invalid charge or model disabled). */
    @JvmStatic
    @JvmOverloads
    fun plan(kg: Double, penetratorDirection: Vec3? = null): Plan? {
        if (!active(kg)) return null
        val parameters = BlastConfig.parameters()
        val direction = penetratorDirection?.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() > 1.0e-8
        }?.normalize()
        return Plan(kg, parameters, BlastModel.radii(kg, parameters), direction)
    }

    /**
     * Charge of a config-backed munition (grenades, mines, C4): an explicit stamp on [entity] (e.g. from a
     * drone attachment or a firing weapon) wins, then the server config override, then the data table [key].
     */
    @JvmStatic
    fun configuredCharge(entity: Entity?, config: ForgeConfigSpec.DoubleValue, key: String): Double =
        entity?.let(TntEquivalents::explicit) ?: TntDefaults.configured(config, key)

    /** Charge of a data-table-only munition (perks, bursts, submunitions). */
    @JvmStatic
    fun tableCharge(key: String): Double = TntDefaults.get(key) ?: 0.0

    /** Visible fireball radius of the charge [entity] carries, or 0 when it uses the legacy blast. */
    @JvmStatic
    fun fireballRadius(entity: Entity?): Double {
        val kg = TntEquivalents.resolve(entity ?: return 0.0)
        if (!active(kg)) return 0.0
        return BlastModel.radius(BlastConfig.parameters().fireballK, kg)
    }

    /** Diameter for size-driven explosion sprites (BVP caliber bursts); 0 = no TNT charge. */
    @JvmStatic
    fun fireballDiameter(entity: Entity?): Float = (2.0 * fireballRadius(entity)).toFloat()

    /**
     * Half of the damaging reach, for far-projectile chunk coverage that assumes a 2R+1 entity query.
     * 0 when the entity carries no TNT charge.
     */
    @JvmStatic
    fun farQueryRadius(entity: Entity?): Double {
        val kg = TntEquivalents.resolve(entity ?: return 0.0)
        if (!active(kg)) return 0.0
        val parameters = BlastConfig.parameters()
        return BlastModel.queryRadius(BlastModel.radii(kg, parameters), parameters) * 0.5
    }

    /**
     * Native explosion recipe for a TNT blast presented out to [presentationRadius]: the larger of the munition's
     * authored recipe and the recipe of that radius, so the TNT model never shrinks an explosion.
     */
    @JvmStatic
    fun particleType(authored: ParticleTool.ParticleType, presentationRadius: Double): ParticleTool.ParticleType {
        val types = ParticleTool.ParticleType.values()
        val byRadius = types[BlastModel.presentationTier(presentationRadius).coerceIn(0, types.size - 1)]
        return if (byRadius.ordinal > authored.ordinal) byRadius else authored
    }

    /** Presentation radius (m) of the charge [entity] carries (severe collapse radius), or 0 for a legacy blast. */
    @JvmStatic
    fun presentationRadius(entity: Entity?): Double {
        val kg = TntEquivalents.resolve(entity ?: return 0.0)
        if (!active(kg)) return 0.0
        return BlastModel.radii(kg, BlastConfig.parameters()).severe
    }
}
