package com.atsuishio.superbwarfare.compat.tacz

import net.minecraft.resources.ResourceLocation

/** Explicit TacZ gameplay policy, not a guessed caliber for unknown ammunition. */
object TaczAircraftDamage {
    // Exact installed TacZ RPG rounds. A physical aircraft hit bypasses ground armor queries,
    // receives this direct damage once, and suppresses the native direct/blast follow-up.
    private val aircraftRocketAmmoIds = setOf("tacz:rpg_rocket", "sbw_flans:pg7v")
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
     * Per physical aircraft hit: RPG rounds take 67% of fighter-class max HP, capped at 110 HP
     * so larger airframes retain their distinct durability. Exact .50/12.7mm ammo identities
     * deal 3 HP, and other TacZ ammunition deals 0.2 HP, including unknown ammo and pellets.
     * The caller supplies EntityKineticBullet's constructor-captured ammo ID; no gun lookup occurs.
     */
    @JvmStatic
    fun damageForAmmo(ammoId: ResourceLocation?, targetMaxHealth: Float): Float = when (ammoId?.toString()) {
        in aircraftRocketAmmoIds -> if (targetMaxHealth.isFinite() && targetMaxHealth > 0F)
            (targetMaxHealth * 0.67F).coerceAtMost(110F) else 0.2F
        in fiftyCalibreAmmoIds -> 3F
        else -> 0.2F
    }
}
