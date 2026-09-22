package com.atsuishio.superbwarfare.api.weapon

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class ShotStatus {
    ACCEPTED,
    PARTIAL,
    REJECTED,
}

enum class ShotRejectionReason {
    NONE,
    WRECKED,
    NO_WEAPON,
    NOT_SERVER_AUTHORITY,
    ACTION_BLOCKED,
    CANNOT_SHOOT,
    PROJECTILE_CREATION_FAILED,
}

/** Immutable server-authored receipt for one logical trigger attempt. */
data class ShotResult(
    val status: ShotStatus,
    val reason: ShotRejectionReason,
    val weaponName: String?,
    val projectileProfileId: ResourceLocation?,
    val muzzlePosition: Vec3?,
    val direction: Vec3?,
    val spawnedProjectileIds: List<UUID>,
) {
    fun isAccepted(): Boolean = status != ShotStatus.REJECTED

    fun withWeaponName(name: String?): ShotResult = copy(weaponName = name)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun rejected(reason: ShotRejectionReason, weaponName: String? = null): ShotResult {
            return ShotResult(ShotStatus.REJECTED, reason, weaponName, null, null, null, emptyList())
        }
    }
}
