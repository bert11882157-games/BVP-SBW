package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

enum class ProjectileTrailKind {
    SMALL,
    MEDIUM,
    LARGE,
}

fun interface ProjectileTrailProvider {
    /** Returns true only after emitting the complete replacement trail. */
    fun emit(projectile: FastThrowableProjectile, kind: ProjectileTrailKind): Boolean
}

/**
 * Client providers are selected by the immutable visual profile carried by the
 * projectile. The registry itself is common-side safe; dedicated servers never
 * register or invoke a provider.
 */
object ProjectileTrailProviders {
    private val providers = ConcurrentHashMap<ResourceLocation, ProjectileTrailProvider>()
    private val fallbackProviders = ConcurrentSkipListMap<String, ProjectileTrailProvider>()

    @JvmStatic
    fun register(id: ResourceLocation, provider: ProjectileTrailProvider) {
        providers[id] = provider
    }

    @JvmStatic
    fun unregister(id: ResourceLocation) {
        providers.remove(id)
    }

    /**
     * Registers a last-resort entity-aware provider for addon projectiles whose synchronized
     * profile is unavailable. Fallbacks run only after normal profile selection cannot emit.
     */
    @JvmStatic
    fun registerFallback(id: ResourceLocation, provider: ProjectileTrailProvider) {
        fallbackProviders[id.toString()] = provider
    }

    @JvmStatic
    fun unregisterFallback(id: ResourceLocation) {
        fallbackProviders.remove(id.toString())
    }

    @JvmStatic
    fun emit(projectile: FastThrowableProjectile, kind: ProjectileTrailKind): Boolean {
        // A typed wire-guided missile owns its propulsion phase.  Once fuel is exhausted, do
        // not invoke a replacement provider that could emit a thrust/flame trail; returning
        // false deliberately falls through to FastThrowable's native smoke-only trail.  Legacy
        // wire missiles without the opt-in descriptor retain their existing provider behavior.
        if (projectile is WireGuideMissileEntity &&
            projectile.hasGuidedPropulsion() &&
            !projectile.isGuidedPropulsionThrusting()
        ) return false

        val profile = ProjectileProfiles.resolve(projectile)
        when (profile?.trailMode ?: ProjectileTrailMode.DEFAULT) {
            ProjectileTrailMode.DEFAULT -> Unit
            ProjectileTrailMode.SUPPRESS -> return true
            ProjectileTrailMode.REPLACE -> {
                val visualId = profile?.visualProfileId
                if (visualId != null && providers[visualId]?.emit(projectile, kind) == true) {
                    return true
                }
            }
        }
        return fallbackProviders.values.any { it.emit(projectile, kind) }
    }

    @JvmStatic
    fun suppressesNativeLaunchFx(entity: Entity): Boolean {
        return when (ProjectileProfiles.resolve(entity)?.trailMode ?: ProjectileTrailMode.DEFAULT) {
            ProjectileTrailMode.DEFAULT -> false
            ProjectileTrailMode.REPLACE, ProjectileTrailMode.SUPPRESS -> true
        }
    }
}
