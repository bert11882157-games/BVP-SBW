package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.MotionSyncPolicy
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation

data class ResolvedProjectileCollision(
    val width: Float,
    val height: Float,
)

/**
 * Immutable profile-generation snapshot. Datapack reloads invalidate the resolver cache, so newly
 * assigned projectiles see new data while in-flight projectiles retain their existing reference.
 */
class ResolvedProjectileProfile internal constructor(
    val id: ResourceLocation,
    val combat: ProjectileCombatDescriptor?,
    val visualProfileId: ResourceLocation?,
    val impactVisualProfileId: ResourceLocation?,
    val motionSync: MotionSyncPolicy,
    val collision: ResolvedProjectileCollision?,
    val luminance: Int,
    val trailMode: ProjectileTrailMode,
    val renderScale: Float,
    extensions: JsonObject,
    val guidedPropulsion: GuidedPropulsionProfile? = null,
) {
    private val extensionData: JsonObject = extensions.deepCopy()

    fun extensions(): JsonObject = extensionData.deepCopy()

    fun extension(id: ResourceLocation): JsonElement? = extensionData.get(id.toString())?.deepCopy()
}
