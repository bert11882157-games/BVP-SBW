package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity

/**
 * The TNT-equivalent charge carried by a munition entity, stored in its Forge persistent data so it
 * survives saves and works for any projectile class (SBW, BVP or external) without an interface change.
 *
 * Tri-state: no tag = not stamped (the entity-type default table may apply), 0 = explicitly none
 * (legacy blast), > 0 = TNT model. Server-side only.
 */
object TntEquivalents {
    const val NBT_KEY = "SbwTntEquivalentKg"

    @JvmStatic
    fun sanitize(kg: Double): Double = if (kg.isFinite() && kg > 0.0) kg.coerceAtMost(TntDefaultTable.MAX_KG) else 0.0

    /** Stamps an explicit charge; 0 (or invalid) marks the munition as carrying no TNT equivalent. */
    @JvmStatic
    fun set(entity: Entity, kg: Double) {
        entity.persistentData.putDouble(NBT_KEY, sanitize(kg))
    }

    @JvmStatic
    fun explicit(entity: Entity): Double? {
        val data = entity.persistentData
        return if (data.contains(NBT_KEY, Tag.TAG_ANY_NUMERIC.toInt())) sanitize(data.getDouble(NBT_KEY)) else null
    }

    /** Stamped charge, else the entity-type table default, else 0. */
    @JvmStatic
    fun resolve(entity: Entity?): Double {
        if (entity == null) return 0.0
        return explicit(entity) ?: TntDefaults.forEntity(entity) ?: 0.0
    }

    /** Copies an explicit stamp (penetration continuations, split projectiles). */
    @JvmStatic
    fun copy(from: Entity, to: Entity) {
        explicit(from)?.let { set(to, it) }
    }

    /** Stamps the charge authored on the firing weapon (0 when the weapon has none). */
    @JvmStatic
    fun stamp(entity: Entity, data: GunData) {
        set(entity, data.get(GunProp.TNT_EQUIVALENT_KG))
    }
}
