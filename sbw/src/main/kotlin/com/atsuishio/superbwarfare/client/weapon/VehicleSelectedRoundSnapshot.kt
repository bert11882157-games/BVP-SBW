package com.atsuishio.superbwarfare.client.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.google.gson.JsonObject
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Current selectable ammunition, separate from the stable base weapon-system metadata. */
data class VehicleSelectedRoundSnapshot(
    val ammoIndex: Int,
    val roundId: ResourceLocation?,
    val type: ProjectileHullDamageClass?,
    /** Authored selected-ammo Name, possibly already containing the type; never the cannon name. */
    val designation: Component?,
) {
    companion object {
        @JvmStatic
        fun capture(data: GunData): VehicleSelectedRoundSnapshot? {
            val consumers = data.get(GunProp.AMMO_CONSUMER)
            val index = data.selectedAmmoType.get()
            val selected = consumers.getOrNull(index) ?: return null
            val name = VehicleSelectedRoundMetadata.designationKey(selected.override, data.getDefault().name)
            // The effective nominal descriptor follows the selected AmmoConsumer override.
            // No firing-belt resolution, advancement or server-only Projectile read is needed.
            val profileId = data.get(GunProp.NOMINAL_BALLISTICS)?.projectileProfile
                ?.let(ResourceLocation::tryParse)
            val combat = ProjectileProfiles.resolve(profileId)?.combat
            return VehicleSelectedRoundSnapshot(index, combat?.roundId, combat?.hullDamageClass,
                name?.let { Component.translatable(it, "") })
        }
    }
}

/** Only an explicit selected-ammo label is a designation; missing labels remain absent. */
object VehicleSelectedRoundMetadata {
    fun designationKey(selectedOverride: JsonObject?, baseWeaponName: String?): String? {
        val value = selectedOverride?.get("Name") ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.trim().takeIf {
            it.isNotEmpty() && it != baseWeaponName?.trim()
        }
    }

    /** Drop a duplicated authored type suffix when the renderer shows that type separately. */
    fun separateDesignation(localizedDesignation: String?, localizedType: String?): String? {
        val designation = localizedDesignation?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = localizedType?.trim()?.takeIf { it.isNotEmpty() } ?: return designation
        if (designation.equals(type, ignoreCase = true)) return null
        val suffix = " $type"
        return if (designation.endsWith(suffix, ignoreCase = true))
            designation.dropLast(suffix.length).trim().takeIf { it.isNotEmpty() }
        else designation
    }
}
