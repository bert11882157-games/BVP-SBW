package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.projectile.MunitionFlightPlayback
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.WeakHashMap

/**
 * Client half of the munition flight stream: owns one [MunitionFlightPlayback] per server-steered munition and,
 * once per client tick after every entity has ticked, places the munition on the played-back path.
 *
 * Running after the entity ticks makes this the last writer of position, velocity and rotation before the frame
 * is drawn: the munition's own client-side tick code (a copy of the server's motor and turn code without its
 * guidance) no longer decides where it is drawn. Highest priority so that later tick listeners, such as the TV
 * seeker view, read the played-back state. Client thread only.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object MunitionFlightClient {
    private val flights = WeakHashMap<FastThrowableProjectile, MunitionFlightPlayback>()

    fun accept(entity: FastThrowableProjectile, sample: MunitionFlightPlayback.Sample) {
        flights.getOrPut(entity) { MunitionFlightPlayback() }.offer(sample)
    }

    /** True while [entity]'s drawn flight comes from the stream; vanilla tracker updates are then ignored. */
    @JvmStatic fun playing(entity: FastThrowableProjectile): Boolean = flights[entity]?.active() == true

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END || flights.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) { flights.clear(); return }
        if (mc.isPaused) return
        val iterator = flights.entries.iterator()
        while (iterator.hasNext()) {
            val (entity, playback) = iterator.next()
            if (entity.isRemoved || entity.level() !== level) { iterator.remove(); continue }
            // A residency pause holds the munition where the server stopped it; playback resumes from new states.
            if (FarProjectilePlayback.pausedVelocity(entity) != null) { playback.reset(); continue }
            val state = playback.advance() ?: continue
            entity.setPos(state.position)
            entity.deltaMovement = state.velocity
            entity.yRot = state.yRot
            entity.xRot = state.xRot
        }
    }

    @SubscribeEvent fun left(event: EntityLeaveLevelEvent) {
        if (event.level.isClientSide) (event.entity as? FastThrowableProjectile)?.let(flights::remove)
    }
}
