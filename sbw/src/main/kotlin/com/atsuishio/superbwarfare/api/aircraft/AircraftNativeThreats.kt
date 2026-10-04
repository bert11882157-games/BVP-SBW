package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.util.UUID
import java.util.WeakHashMap

/** Optional FFA bridge, sampled after missile ticks so diversion/loss cannot leave a stale warning. */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object AircraftNativeThreats {
    private val missiles = WeakHashMap<MissileProjectile, Boolean>()
    private val report by lazy { runCatching {
        Class.forName("dev.ballistics.AircraftThreatHooks").getMethod("guidanceReport", Entity::class.java, Entity::class.java)
    }.getOrNull() }
    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        if (!event.level.isClientSide) (event.entity as? MissileProjectile)?.let { missiles[it] = true }
    }
    @SubscribeEvent fun left(event: EntityLeaveLevelEvent) {
        (event.entity as? MissileProjectile)?.let { missiles.remove(it); runCatching { report?.invoke(null, it, null) } }
    }
    @SubscribeEvent fun tick(event: TickEvent.LevelTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val level = event.level as? ServerLevel ?: return
        for (missile in missiles.keys.toList()) {
            if (missile.level() !== level) continue
            runCatching { report?.invoke(null, missile, target(missile, level)) }
        }
    }
    /** Each native missile in [level] still homing, with the entity it homes on (see [IncomingMissileWarning]). */
    internal fun forEachHoming(level: ServerLevel, action: (MissileProjectile, Entity) -> Unit) {
        for (missile in missiles.keys.toList()) {
            if (missile.level() !== level) continue
            target(missile, level)?.let { action(missile, it) }
        }
    }
    private fun target(missile: MissileProjectile, level: ServerLevel): Entity? {
        val id = when {
            missile.isRemoved || missile.lost || missile.lostTarget || missile.distracted -> null
            missile is WireGuideMissileEntity -> missile.actualGuidanceTargetUUID
            else -> runCatching { UUID.fromString(missile.targetUUID) }.getOrNull()
        }
        return id?.let(level::getEntity)?.takeIf { it.isAlive && !it.isRemoved }
    }
    @SubscribeEvent fun stopped(event: ServerStoppedEvent) { missiles.clear() }
}
