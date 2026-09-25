package com.atsuishio.superbwarfare.event

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.tools.CustomExplosion
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Projectile
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/**
 * Enforces `explosion.munition_block_damage = false` on explosions that do not come from [CustomExplosion]'s own
 * gated block collection: vanilla/Forge explosions set off by an SBW or BVP entity (vehicles, rockets, the
 * Annihilator), and explosions of other mods' projectiles (gun and missile mods). Their block list is emptied, which
 * also stops the fire an incendiary explosion would place. Vanilla projectiles (ghast fireballs, wither skulls) and
 * non-projectile explosions (TNT, creepers, beds) are left alone.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
object MunitionBlockDamageGuard {
    private val OWN_NAMESPACES = setOf(Mod.MODID, "berts_vehicle_pack")

    @JvmStatic
    fun isMunition(entity: Entity?): Boolean {
        if (entity == null) return false
        val namespace = EntityType.getKey(entity.type).namespace
        if (namespace in OWN_NAMESPACES) return true
        return entity is Projectile && namespace != "minecraft"
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onDetonate(event: ExplosionEvent.Detonate) {
        if (event.level.isClientSide || ExplosionConfig.MUNITION_BLOCK_DAMAGE.get()) return
        val explosion = event.explosion
        if (explosion is CustomExplosion || isMunition(explosion.directSourceEntity) || isMunition(explosion.exploder)) {
            event.affectedBlocks.clear()
        }
    }
}
