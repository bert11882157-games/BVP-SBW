package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftSeekerSounds {
    private var playing: ToneSound? = null
    private val confirmation = AircraftLockConfirmation()
    fun update(seek: AircraftSeekView?) {
        val mc = Minecraft.getInstance()
        if (AircraftSeekerPresentation.tone(seek) != AircraftSeekerPresentation.Tone.LOCK) {
            playing?.cancel(); playing = null
        }
        if (confirmation.update(seek, mc.level?.gameTime ?: 0L)) {
            playing?.cancel()
            playing = ToneSound().also { mc.soundManager.play(it) }
        }
    }
    fun reset() { playing?.cancel(); playing = null; confirmation.reset() }
    @SubscribeEvent fun sourceReady(event: PlaySoundSourceEvent) {
        val tone = event.sound as? ToneSound ?: return
        if (tone.cancelled) event.channel.stop()
    }
    private class ToneSound : AbstractTickableSoundInstance(
        SoundEvent.createVariableRangeEvent(ResourceLocation(Mod.MODID, "aam_lock")),
        SoundSource.PLAYERS, RandomSource.create()) {
        @Volatile var cancelled = false; private set
        init { looping = false; delay = 0; volume = 0.35F; pitch = 1F; relative = true; attenuation = SoundInstance.Attenuation.NONE }
        override fun canPlaySound() = !cancelled
        override fun tick() {
            val mc = Minecraft.getInstance()
            if (mc.level == null || mc.player?.isAlive != true || mc.player?.isPassenger != true || playing !== this) cancel()
        }
        fun cancel() { if (!cancelled) { cancelled = true; stop(); Minecraft.getInstance().soundManager.stop(this) } }
    }
}
