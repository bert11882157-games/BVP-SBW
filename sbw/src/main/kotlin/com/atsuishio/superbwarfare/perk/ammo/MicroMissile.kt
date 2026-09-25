package com.atsuishio.superbwarfare.perk.ammo

import com.atsuishio.superbwarfare.data.PMC
import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.perk.AmmoPerk
import com.atsuishio.superbwarfare.perk.PerkInstance
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import com.atsuishio.superbwarfare.tools.blast.TntEquivalents
import net.minecraft.world.entity.Entity

object MicroMissile : AmmoPerk(Builder("micro_missile", Type.AMMO).speedRate(1.2)) {
    override fun modifyProjectile(
        data: GunData,
        instance: PerkInstance,
        entity: Entity
    ) {
        entity.isNoGravity = true
        if (entity.level().isClientSide) return
        // A data-table charge replaces the weapon's; either way it scales like the explosion damage.
        val base = TntBlast.tableCharge(TntBlast.MICRO_MISSILE_KEY).takeIf { it > 0.0 } ?: TntEquivalents.resolve(entity)
        TntEquivalents.set(entity, base * (0.8 + data.perk.getLevel(this) * 0.1))
    }

    override fun modifyProperty(modifier: PMC<GunData, DefaultGunData>) {
        super.modifyProperty(modifier)
        with(GunProp) {
            modifier[EXPLOSION_DAMAGE] *= (0.8 + modifier.data.perk.getLevel(this@MicroMissile) * 0.1)
            modifier[EXPLOSION_RADIUS] *= 0.5
            modifier[GRAVITY] = 0.0
        }
    }
}
