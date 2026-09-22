package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Client-only owner for native vehicle loop instances.
 *
 * Vehicle ticks may request the same channel every tick. A live SoundManager instance is reused;
 * a stopped/culled instance is recreated without waiting for an engine-state edge. The cache is
 * exact-entity keyed, level scoped, and bounded so entity-id/UUID reuse cannot revive an old loop.
 */
@OnlyIn(Dist.CLIENT)
object VehicleNativeLoopSoundLifecycle {
    enum class Channel { ENGINE, TRACK, SWIM }

    private data class Key(val vehicleUuid: UUID, val channel: Channel)
    private data class ActiveLoop(
        val vehicle: VehicleEntity,
        val level: ClientLevel,
        val sound: VehicleSoundInstance,
        var lastSeenGameTime: Long,
        val nextRetryGameTime: Long,
    )

    private val active = LinkedHashMap<Key, ActiveLoop>(32, 0.75f, true)
    private var level: ClientLevel? = null

    @JvmStatic
    fun ensure(vehicle: VehicleEntity, channel: Channel, factory: () -> VehicleSoundInstance) {
        val minecraft = Minecraft.getInstance()
        val currentLevel = minecraft.level ?: run { clear(minecraft); return }
        if (level !== currentLevel) {
            clear(minecraft)
            level = currentLevel
        }
        if (vehicle.level() !== currentLevel || vehicle.isRemoved) return

        val now = currentLevel.gameTime
        val key = Key(vehicle.uuid, channel)
        val previous = active[key]
        if (previous != null && previous.vehicle === vehicle && previous.level === currentLevel) {
            previous.lastSeenGameTime = now
            if (!previous.sound.isStopped && minecraft.soundManager.isActive(previous.sound)) return
            // SoundManager registration is asynchronous. A non-stopped instance gets a short
            // bounded grace period; a definitively stopped instance restarts immediately.
            if (!previous.sound.isStopped && now < previous.nextRetryGameTime) return
        }

        previous?.sound?.let(minecraft.soundManager::stop)
        val sound = factory()
        minecraft.soundManager.play(sound)
        active[key] = ActiveLoop(vehicle, currentLevel, sound, now, now + REGISTRATION_GRACE_TICKS)
        trim(minecraft)
    }

    /** Called once per client END tick, including menu/world teardown. */
    @JvmStatic
    fun tick() {
        val minecraft = Minecraft.getInstance()
        val currentLevel = minecraft.level
        if (currentLevel == null || level !== currentLevel) {
            clear(minecraft)
            level = currentLevel
            return
        }
        val now = currentLevel.gameTime
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.vehicle.isRemoved || entry.vehicle.level() !== currentLevel ||
                now - entry.lastSeenGameTime > UNSEEN_TTL_TICKS
            ) {
                minecraft.soundManager.stop(entry.sound)
                iterator.remove()
            }
        }
    }

    private fun trim(minecraft: Minecraft) {
        while (active.size > MAX_ACTIVE_LOOPS) {
            val eldest = active.entries.iterator()
            if (!eldest.hasNext()) return
            val entry = eldest.next()
            minecraft.soundManager.stop(entry.value.sound)
            eldest.remove()
        }
    }

    private fun clear(@Suppress("UNUSED_PARAMETER") minecraft: Minecraft) {
        active.values.forEach { minecraft.soundManager.stop(it.sound) }
        active.clear()
    }

    private const val REGISTRATION_GRACE_TICKS = 5L
    private const val UNSEEN_TTL_TICKS = 2L
    private const val MAX_ACTIVE_LOOPS = 256
}
