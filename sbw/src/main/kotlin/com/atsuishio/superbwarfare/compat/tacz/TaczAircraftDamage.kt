package com.atsuishio.superbwarfare.compat.tacz

import net.minecraft.resources.ResourceLocation

/** Explicit TacZ gameplay policy, not a guessed caliber for unknown ammunition. */
object TaczAircraftDamage {
    private val fiftyCalibreAmmoIds = setOf(
        "tacz:50bmg",
        "tacz:50ae",
        "tacz:500mag",
        "ea:50gi",
        "ea:50beowulf",
        "ea:127x55",
        "ea:127x108",
        "suffuse:12.7x55",
        "suffuse:12.7x108mm",
        "sbw_flans:50_action_express",
    )

    /**
     * Per physical projectile hit: exact installed .50/12.7mm ammo identities are 3 HP; all other
     * TacZ ammunition is 0.2 HP, including unknown ammo, individual pellets, and profiled rounds.
     * The caller supplies EntityKineticBullet's constructor-captured ammo ID; no gun lookup occurs.
     */
    @JvmStatic
    fun damageForAmmo(ammoId: ResourceLocation?): Float =
        if (ammoId?.toString() in fiftyCalibreAmmoIds) 3F else 0.2F
}
