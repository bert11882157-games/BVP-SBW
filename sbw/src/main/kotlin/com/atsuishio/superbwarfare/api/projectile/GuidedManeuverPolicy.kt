package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import kotlin.math.cos

/** Optional physical maneuver limit. One block is one metre; velocity is blocks per game tick. */
data class GuidedManeuverPolicy @JvmOverloads constructor(val maxLoadFactorG: Double, val seekerConeDegrees: Double,
    val seekerRateDegrees: Double, val bodyTurnLimitScale: Double = 1.0) {
    init { require(bodyTurnLimitScale.isFinite() && bodyTurnLimitScale in 1.0..3.0) }
    /** Explicit opt-in scales the old angular ceiling, not the separately authored G limit. */
    val bodyTurnCeiling: Double get() = GuidedPropulsionProfile.MAX_TURN_RATE_DEGREES_PER_SECOND * bodyTurnLimitScale
    fun effectiveTurnRate(speedBlocksPerTick: Double, configuredTurnRate: Double): Double =
        minOf(turnRate(speedBlocksPerTick), configuredTurnRate * bodyTurnLimitScale)
    fun turnRate(speedBlocksPerTick: Double): Double {
        if (!speedBlocksPerTick.isFinite() || speedBlocksPerTick <= 0) return 0.0
        return Math.toDegrees(maxLoadFactorG * 9.80665 / (speedBlocksPerTick * 20.0)).coerceAtMost(bodyTurnCeiling)
    }
    fun captures(forward: Vec3, desired: Vec3): Boolean = forward.lengthSqr() > 1e-12 && desired.lengthSqr() > 1e-12 &&
        forward.normalize().dot(desired.normalize()) >= cos(Math.toRadians(seekerConeDegrees))
    companion object {
        val ID = ResourceLocation("superbwarfare", "guided_maneuver_v1")
        fun from(profile: ResolvedProjectileProfile?): GuidedManeuverPolicy? = runCatching {
            val j = profile?.extension(ID)?.asJsonObject ?: return null
            val g = j["MaxLoadFactorG"].asDouble; val cone = j["SeekerConeDegrees"].asDouble
            val rate = j["SeekerRateDegreesPerSecond"].asDouble
            require(g.isFinite() && g in 0.1..50.0 && cone.isFinite() && cone in 1.0..90.0 && rate.isFinite() && rate in 0.1..180.0)
            GuidedManeuverPolicy(g, cone, rate, j["BodyTurnLimitScale"]?.asDouble ?: 1.0)
        }.getOrNull()
    }
}
