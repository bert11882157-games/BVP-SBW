package com.atsuishio.superbwarfare.api.audio

import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.network.message.receive.SpatialAudioMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * One place for positional combat audio (gunfire, launches, explosions). The server only says *what* happened and
 * where; every client picks what it hears:
 *
 *  - the crew of the source vehicle (and the operator of a placed weapon) hear the interior / first-person variant
 *    when one is authored, immediately and without distance loss;
 *  - everyone else hears the near, far or very-far variant for their distance, cross-faded at the band edges, with
 *    a sqrt distance law (gain = sqrt(ref / d)) faded to silence at [Cue.maxRange], delayed by the speed of sound,
 *    Doppler-shifted by the source's motion, and limited by a client voice pool.
 *
 * Players beyond the maximum hearing distance are never sent the event.
 */
object SpatialAudio {
    enum class Category(val cap: Int) {
        /** Weapon discharge, launches. */
        WEAPON(40),
        /** Detonations and impacts. */
        EXPLOSION(24),
        /** Everything else (mechanical one-shots). */
        MECHANICAL(16),
    }

    /**
     * The variants of one event, near to far, and where each band ends (blocks). Missing variants fall back to the
     * nearest authored one.
     */
    data class Cue(
        val firstPerson: SoundEvent?,
        val near: SoundEvent?,
        val far: SoundEvent?,
        val veryFar: SoundEvent?,
        val nearRange: Float,
        val farRange: Float,
        val maxRange: Float,
    )

    private fun SoundEvent?.usable() = this != null && location != SoundEvents.EMPTY.location

    /**
     * Sends [cue] at [pos] to every player that can hear it. [source] is the moving thing making the sound (its
     * motion drives Doppler and identifies its crew); [operator] always hears it, even out of range.
     */
    @JvmStatic
    @JvmOverloads
    fun emit(
        level: ServerLevel,
        pos: Vec3,
        cue: Cue,
        gain: Float = 1f,
        pitch: Float = 1f,
        source: Entity? = null,
        operator: Entity? = null,
        category: Category = Category.WEAPON,
        weapon: String? = null,
    ) {
        if (!(cue.firstPerson.usable() || cue.near.usable() || cue.far.usable() || cue.veryFar.usable())) return
        if (!pos.x.isFinite() || !pos.y.isFinite() || !pos.z.isFinite()) return
        val maxRange = cue.maxRange.coerceIn(8f, 2048f)
        val nearRange = cue.nearRange.coerceIn(4f, maxRange)
        val farRange = cue.farRange.coerceIn(nearRange, maxRange)
        val message = SpatialAudioMessage(
            cue.firstPerson.takeIf { it.usable() }?.location,
            cue.near.takeIf { it.usable() }?.location,
            cue.far.takeIf { it.usable() }?.location,
            cue.veryFar.takeIf { it.usable() }?.location,
            pos.x, pos.y, pos.z, nearRange, farRange, maxRange,
            gain.coerceIn(0f, 4f), pitch.coerceIn(0.25f, 4f),
            source?.id ?: -1, operator?.id ?: -1, category.ordinal, weapon?.take(96),
        )
        val reach = (maxRange + 16.0) * (maxRange + 16.0)
        var sent = 0
        for (player in level.players()) {
            val crew = source != null && (player.vehicle === source || player.rootVehicle === source)
            if (player === operator || crew || player.distanceToSqr(pos) <= reach) {
                sendPacketTo(player, message)
                sent++
            }
        }
        NetworkTelemetry.recordSystemWork("fx.spatial_audio", items = sent, recipients = sent)
    }

    /** Legacy three-tier weapon sound info (3P / far / very far radii in "sound radius" units of 16 blocks). */
    @JvmStatic
    fun weaponCue(
        firstPerson: SoundEvent?, near: SoundEvent?, far: SoundEvent?, veryFar: SoundEvent?,
        nearRadius: Float, farRadius: Float, veryFarRadius: Float,
    ): Cue {
        // the old radii were linear-attenuation ranges; the sqrt law carries further, so the bands keep their reach
        val max = (maxOf(nearRadius, farRadius, veryFarRadius) * 16f).coerceAtLeast(32f)
        val nearEnd = (nearRadius * 16f).coerceIn(12f, max)
        val farEnd = (farRadius * 16f).coerceIn(nearEnd, max)
        return Cue(firstPerson, near, far, veryFar, nearEnd, farEnd, max)
    }

    /** Server-side helper for a single player (the operator), e.g. a first-person-only cue. */
    @JvmStatic
    fun emitTo(player: ServerPlayer, pos: Vec3, cue: Cue, gain: Float = 1f, pitch: Float = 1f, source: Entity? = null) {
        sendPacketTo(player, SpatialAudioMessage(
            cue.firstPerson?.location, cue.near?.location, cue.far?.location, cue.veryFar?.location,
            pos.x, pos.y, pos.z, cue.nearRange, cue.farRange, cue.maxRange, gain, pitch,
            source?.id ?: -1, player.id, Category.MECHANICAL.ordinal, null,
        ))
    }
}
