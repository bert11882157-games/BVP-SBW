package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.network.message.receive.FarProjectileStateMessage
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import net.minecraft.world.level.Level
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import com.atsuishio.superbwarfare.Mod

/** Paused real entities retain their native renderer; only authoritative simulation may resume them. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FarProjectilePlayback {
    private data class PausedFlight(val position: Vec3, val velocity: Vec3)
    private val paused = WeakHashMap<Entity, PausedFlight>()
    private val pending = WeakHashMap<Level, LinkedHashMap<Int, FarProjectileStateMessage>>()

    fun accept(message: FarProjectileStateMessage) {
        val level = Minecraft.getInstance().level ?: return
        val entity = level.getEntity(message.id)
        if (entity == null) {
            // Forge spawn and custom state packets can enqueue on different client work paths.
            val queue = pending.getOrPut(level) { LinkedHashMap() }
            if (queue.size >= 4096) queue.remove(queue.keys.first())
            queue[message.id] = message
            return
        }
        apply(entity, message)
    }

    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        if (!event.level.isClientSide) return
        pending[event.level]?.remove(event.entity.id)?.let { apply(event.entity, it) }
    }

    @SubscribeEvent fun left(event: EntityLeaveLevelEvent) {
        if (!event.level.isClientSide) return
        paused.remove(event.entity)
        pending[event.level]?.remove(event.entity.id)
    }

    private fun apply(entity: Entity, message: FarProjectileStateMessage) {
        if (entity !is FarProjectileAccess || entity.stringUUID != message.uuid || entity.isRemoved) return
        val position = Vec3(message.x, message.y, message.z)
        entity.setPos(position)
        entity.xo = message.x; entity.yo = message.y; entity.zo = message.z
        entity.xOld = message.x; entity.yOld = message.y; entity.zOld = message.z
        entity.deltaMovement = if (message.paused) Vec3.ZERO else Vec3(message.vx, message.vy, message.vz)
        if (message.paused) paused[entity] = PausedFlight(position, Vec3(message.vx, message.vy, message.vz))
        else paused.remove(entity)
        EliteDiagnostics.record(entity, "far_projectile", "CLIENT_SIMULATION_STATE", "paused", message.paused,
            "position", position, "velocity", entity.deltaMovement)
    }

    /** Keep the visible tracer's heading while collision residency pauses actual movement. */
    @JvmStatic fun pausedVelocity(entity: Entity): Vec3? = paused[entity]?.velocity

    @JvmStatic fun hold(entity: Entity): Boolean {
        val position = paused[entity]?.position ?: return false
        entity.setPos(position)
        entity.deltaMovement = Vec3.ZERO
        entity.xo = position.x; entity.yo = position.y; entity.zo = position.z
        entity.xOld = position.x; entity.yOld = position.y; entity.zOld = position.z
        return true
    }
}
