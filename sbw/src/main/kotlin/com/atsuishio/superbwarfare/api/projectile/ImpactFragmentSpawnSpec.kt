package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3

/** Gameplay inputs are pinned independently of optional visual scale and tracer extensions. */
data class ImpactFragmentSpawnSpec(
    val position: Vec3,
    val direction: Vec3,
    val speedBlocksPerTick: Float,
    val lifetimeTicks: Int,
    val damage: Float,
    val gameplayProfile: ResolvedProjectileProfile,
    val renderScale: Float,
    val shotSequence: Long,
) {
    fun isValid(): Boolean = finite(position) && finite(direction) && direction.lengthSqr() > 1.0E-8
        && speedBlocksPerTick.isFinite() && speedBlocksPerTick > 0f && lifetimeTicks in 1..16
        && damage.isFinite() && damage >= 0f && gameplayProfile.combat != null

    private fun finite(value: Vec3) = value.x.isFinite() && value.y.isFinite() && value.z.isFinite()
}
