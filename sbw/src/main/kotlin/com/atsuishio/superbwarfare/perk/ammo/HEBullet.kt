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

object HEBullet : AmmoPerk(
    Builder("he_bullet", Type.AMMO).bypassArmorRate(-0.3).damageRate(0.5).speedRate(0.85).slug().rgb(240, 20, 10)
) {
    override fun modifyProperty(modifier: PMC<GunData, DefaultGunData>) {
        super.modifyProperty(modifier)
        with(GunProp) {
            modifier[EXPLOSION_DAMAGE] =
                (0.9 * modifier[DAMAGE] * 2) * (1 + 0.1 * modifier.data.perk.getLevel(this@HEBullet))
            modifier[EXPLOSION_RADIUS] = (1.7 + 0.3 * modifier.data.perk.getLevel(this@HEBullet))
        }
    }

    /** The HE filler is a fixed data-table charge; without an entry the round keeps the legacy blast. */
    override fun modifyProjectile(data: GunData, instance: PerkInstance, entity: Entity) {
        super.modifyProjectile(data, instance, entity)
        if (entity.level().isClientSide) return
        TntEquivalents.set(entity, TntBlast.tableCharge(TntBlast.HE_BULLET_KEY))
    }
}
