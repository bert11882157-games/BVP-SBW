package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.Vec3

/** Projectile admission depends on proximity and bounded sweep size, never visual cover. */
internal object FarProjectilePolicyRange {
    fun contains(observerX: Double, observerZ: Double, radius: Int,
                 from: Vec3, to: Vec3, chunks: Set<Long>): Boolean =
        radius > 0 && chunks.isNotEmpty() && chunks.size <= FarTerrainResidency.PROJECTILE_CHUNKS &&
            from.y.isFinite() && to.y.isFinite() &&
            FarTerrainPolicy.inside(observerX, observerZ, from.x, from.z, radius) &&
            FarTerrainPolicy.inside(observerX, observerZ, to.x, to.z, radius)
}
