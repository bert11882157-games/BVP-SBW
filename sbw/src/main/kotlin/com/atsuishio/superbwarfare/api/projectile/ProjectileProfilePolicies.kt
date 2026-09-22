package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltTracer
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation

enum class ProjectilePresentationPurpose { SHOT_TRACER, SMALL_WHITE_TRACER, IMPACT_FRAGMENT }
enum class ProjectileRoundQuery { CYCLIC_145, SMALL_WHITE_TRACER }
enum class ProjectileTerrainDecision { INHERIT, KEEP, DEFAULT }

/** Immutable inputs; providers never receive a live entity or authority to mutate gameplay. */
data class ProjectilePresentationInput(
    val template: ResolvedProjectileProfile,
    val purpose: ProjectilePresentationPurpose,
    val tracer: ProjectileBeltTracer?,
    val renderScale: Float,
)

data class ProjectileTerrainInput(
    val profileId: ResourceLocation?,
    val weaponId: ResourceLocation?,
    val vehicleMountedOwner: Boolean,
)

/** A patch cannot replace combat, collision, motion, luminance, or projectile identity. */
class ProjectilePresentationPatch(
    val trailMode: ProjectileTrailMode,
    val renderScale: Float,
    extensions: JsonObject,
) {
    private val extensionData = extensions.deepCopy()
    init { require(renderScale.isFinite() && renderScale > 0f) { "Render scale must be finite and positive" } }
    fun extensions(): JsonObject = extensionData.deepCopy()
}

interface ProjectileProfilePolicy {
    /** Optional cosmetic patch. Runtime failures are logged and the next provider is tried. */
    fun presentation(input: ProjectilePresentationInput): ProjectilePresentationPatch?

    /** Pure identity query; provider failures propagate because the answer affects gameplay. */
    fun roundMatches(query: ProjectileRoundQuery, roundId: ResourceLocation): Boolean

    /** First non-INHERIT decision wins. Provider failures are not converted to gameplay defaults. */
    fun terrain(input: ProjectileTerrainInput): ProjectileTerrainDecision
}

/**
 * Common-side addon policies, registered during setup and queried on either logical side.
 * Providers must be side-effect free and safe for concurrent calls. Registry changes affect the
 * next immutable provider snapshot; first registration order determines presentation precedence.
 */
object ProjectileProfilePolicies {
    private val logger = LogUtils.getLogger()
    private val providers = OrderedProviderRegistry<ResourceLocation, ProjectileProfilePolicy>()

    @JvmStatic fun register(id: ResourceLocation, provider: ProjectileProfilePolicy) = providers.register(id, provider)
    @JvmStatic fun unregister(id: ResourceLocation): Boolean = providers.unregister(id)

    @JvmStatic fun presentation(input: ProjectilePresentationInput): ProjectilePresentationPatch? {
        for ((id, provider) in providers.snapshot()) {
            try {
                provider.presentation(input)?.let { return it }
            } catch (failure: RuntimeException) {
                logger.warn("Projectile presentation policy {} failed for {}", id, input.template.id, failure)
            }
        }
        return null
    }

    @JvmStatic fun roundMatches(query: ProjectileRoundQuery, roundId: ResourceLocation?): Boolean {
        if (roundId == null) return false
        return providers.snapshot().values.any { it.roundMatches(query, roundId) }
    }

    @JvmStatic fun terrain(input: ProjectileTerrainInput): ProjectileTerrainDecision {
        for (provider in providers.snapshot().values) {
            val decision = provider.terrain(input)
            if (decision != ProjectileTerrainDecision.INHERIT) return decision
        }
        return ProjectileTerrainDecision.INHERIT
    }
}
