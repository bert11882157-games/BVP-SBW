package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.resources.ResourceLocation

/** Public provenance contract shared by SBW projectile hierarchies. */
interface ProfiledProjectile {
    fun getProjectileProfileId(): ResourceLocation?

    fun getResolvedProjectileProfile(): ResolvedProjectileProfile?

    fun setProjectileProfileId(id: ResourceLocation?)

    fun copyProjectileProfileFrom(source: ProfiledProjectile) {
        setProjectileProfileId(source.getProjectileProfileId())
    }
}

/** Optional cadence provenance for projectiles that support per-round presentation. */
interface SequencedProjectile {
    fun getProjectileShotSequence(): Long

    fun setProjectileShotSequence(sequence: Long)
}
