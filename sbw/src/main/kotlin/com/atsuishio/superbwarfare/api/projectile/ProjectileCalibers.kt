package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.projectile.Projectile
import java.util.concurrent.ConcurrentSkipListMap

/** Resolves the fired projectile's physical diameter in millimetres from authoritative metadata. */
fun interface ProjectileCaliberProvider {
    /** Null means this provider does not own the projectile. Invalid non-null values fail closed. */
    fun caliberMillimetres(projectile: Projectile): Double?
}

/**
 * Common-side addon seam for projectiles without an SBW combat snapshot, including infantry ammo.
 * Providers must use the fired round's immutable identity, never current shooter equipment,
 * display-name parsing, visual size, or damage. Registration is setup-only; resolution is server-only.
 */
object ProjectileCalibers {
    private const val AUTHORED_CALIBER = "SBWAuthoredCaliberMm"
    private val providers = ConcurrentSkipListMap<String, ProjectileCaliberProvider>()

    /** Store only the fired definition, never infer from a live weapon/name or damage value. */
    @JvmStatic fun capture(projectile: Projectile, caliber: Double?) {
        capture(projectile.persistentData, caliber)
    }

    internal fun capture(data: net.minecraft.nbt.CompoundTag, caliber: Double?) {
        if (caliber == null) data.remove(AUTHORED_CALIBER)
        else data.putDouble(AUTHORED_CALIBER,
            caliber.takeIf { it.isFinite() && it in 1.0..1000.0 } ?: Double.NaN)
    }

    internal fun authored(data: net.minecraft.nbt.CompoundTag): Double? =
        if (data.contains(AUTHORED_CALIBER, net.minecraft.nbt.Tag.TAG_DOUBLE.toInt())) data.getDouble(AUTHORED_CALIBER) else null

    @JvmStatic
    fun register(id: ResourceLocation, provider: ProjectileCaliberProvider) {
        check(providers.putIfAbsent(id.toString(), provider) == null) {
            "Duplicate projectile caliber provider: $id"
        }
    }

    @JvmStatic
    fun resolveMillimetres(projectile: Projectile): Double? {
        if (projectile.level().isClientSide) return null
        ProjectileProfiles.combatDescriptor(projectile)?.let {
            // Explicit body diameter wins for a missile fired through a differently sized launcher.
            return it.diameterMm ?: it.caliberMm
        }
        authored(projectile.persistentData)?.let { return it }
        for ((id, provider) in providers) {
            val caliber = try {
                provider.caliberMillimetres(projectile)
            } catch (failure: RuntimeException) {
                if (EliteDiagnostics.isEnabled(projectile.level())) {
                    EliteDiagnostics.record(projectile, "aircraft_damage", "CALIBER_PROVIDER_FAILED",
                        "provider", id, "error", failure.javaClass.simpleName)
                }
                return Double.NaN
            }
            caliber?.let { return it }
        }
        return null
    }
}
