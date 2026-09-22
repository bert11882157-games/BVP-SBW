package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import net.minecraft.resources.ResourceLocation

/** Minimal server-side shot identity available before a fired-visual record is published. */
data class FiredVisualInterestContext(
    val weaponId: ResourceLocation?,
    val projectileProfileId: ResourceLocation?,
)

fun interface FiredVisualInterest {
    /** Returns true only when this consumer needs a fired-visual record for the shot. */
    fun isInterested(context: FiredVisualInterestContext): Boolean
}

/**
 * Server-side opt-in registry for fired-visual publication.
 *
 * An empty registry, or a shot rejected by every registered interest, publishes no
 * packet. This keeps the optional presentation channel dormant for native shots
 * unless an addon explicitly declares that it will consume their records.
 */
object FiredVisualInterests {
    private val interests = OrderedProviderRegistry<ResourceLocation, FiredVisualInterest>()

    @JvmStatic
    fun register(id: ResourceLocation, interest: FiredVisualInterest) = interests.register(id, interest)

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = interests.unregister(id)

    @JvmStatic
    fun hasInterest(context: FiredVisualInterestContext): Boolean {
        for ((id, interest) in interests.snapshot()) {
            try {
                if (interest.isInterested(context)) return true
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn(
                    "Fired visual interest {} failed for weapon {} and projectile profile {}",
                    id,
                    context.weaponId,
                    context.projectileProfileId,
                    exception,
                )
            }
        }
        return false
    }
}
