package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.network.message.receive.MunitionFlightMessage
import com.atsuishio.superbwarfare.tools.sendPacketToTrackingThis
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.server.ServerLifecycleHooks
import java.util.Collections
import java.util.WeakHashMap

/**
 * Server half of the munition flight stream. A munition that [FastThrowableProjectile.interpolatesFlight] queues
 * itself while it ticks; after every level has ticked, its end-of-tick state is published once, stamped with the
 * server tick counter. Publishing after the whole tick (not from inside the projectile's tick) means subclass
 * guidance, motors and rotation that run after the base tick are part of the published state.
 *
 * Server thread only. The queue holds only projectiles that ticked this server tick and is cleared on every flush.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object MunitionFlightSync {
    private val pending: MutableSet<FastThrowableProjectile> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic fun queue(projectile: FastThrowableProjectile) {
        if (projectile.isRemoved || projectile.level() !is ServerLevel) return
        pending.add(projectile)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return
        val stamp = ServerLifecycleHooks.getCurrentServer()?.tickCount ?: return
        // Copy first: a send cannot re-enter, but the weak set must not be iterated while it may be collected.
        val batch = pending.toList()
        pending.clear()
        for (projectile in batch) {
            if (projectile.isRemoved || projectile.level() !is ServerLevel) continue
            NetworkTelemetry.recordSystemWork("projectile.flight_state")
            projectile.sendPacketToTrackingThis(MunitionFlightMessage.of(projectile, stamp))
        }
    }
}
