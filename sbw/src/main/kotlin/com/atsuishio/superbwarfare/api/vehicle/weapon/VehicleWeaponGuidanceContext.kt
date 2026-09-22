package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltResolutionStatus
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One immutable Crosshair-A ray; origin and direction are selected as a single authority. */
data class VehicleHudAimRay(
    val origin: Vec3,
    val direction: Vec3,
) {
    init {
        require(origin.x.isFinite() && origin.y.isFinite() && origin.z.isFinite()) {
            "HUD aim origin must be finite"
        }
        require(direction.x.isFinite() && direction.y.isFinite() && direction.z.isFinite()) {
            "HUD aim direction must be finite"
        }
        require(direction.lengthSqr() > 1.0E-12) {
            "HUD aim direction must be non-zero"
        }
    }
}

/**
 * Immutable launch identity used by server-side wire-guided steering.  The slot and id are
 * deliberately kept together for persistence/replay validation.  Guidance never re-resolves
 * the current selected weapon and never hands authority to another occupant after launch.
 */
data class VehicleWeaponGuidanceContext(
    val launcherVehicleUUID: UUID,
    val launcherControllerUUID: UUID?,
    val seatIndex: Int,
    val weaponIndex: Int,
    val weaponName: String,
    val helicopterAtgm: Boolean,
    val roundId: ResourceLocation? = null,
) {
    init {
        require(seatIndex >= 0) { "seatIndex must be non-negative" }
        require(weaponIndex >= 0) { "weaponIndex must be non-negative" }
        require(weaponName.isNotBlank()) { "weaponName must not be blank" }
        require(!helicopterAtgm || roundId != null) {
            "helicopter ATGM guidance requires a typed projectile round id"
        }
        require(!helicopterAtgm || launcherControllerUUID != null) {
            "helicopter ATGM guidance requires an immutable launcher controller"
        }
    }
}

/** Typed projectile classification shared by scheduler and missile guidance. */
object VehicleWeaponGuidance {
    private val atgmMunitionType = ResourceLocation("berts_vehicle_pack", "atgm")

    @JvmStatic
    fun isAtgm(data: GunData): Boolean {
        return atgmRoundId(data) != null
    }

    /** Stable cross-seat identity for a typed wire-guided munition. */
    @JvmStatic
    fun atgmRoundId(data: GunData): ResourceLocation? {
        val belt = data.resolveProjectileBelt()
        val effectiveData = when (belt.status) {
            ProjectileBeltResolutionStatus.NONE -> data
            ProjectileBeltResolutionStatus.READY -> belt.projectileData ?: return null
            ProjectileBeltResolutionStatus.INVALID -> return null
        }
        val projectile = effectiveData.get(GunProp.PROJECTILE)
        val profileId = projectile.profile
            ?: CustomData.LAUNCHABLE_ENTITY[projectile.itemId.trim()]?.profile
            ?: return null
        val combat = ProjectileProfiles.resolve(profileId)?.combat ?: return null
        if (combat.munitionType != atgmMunitionType) return null
        return combat.roundId
    }
}
