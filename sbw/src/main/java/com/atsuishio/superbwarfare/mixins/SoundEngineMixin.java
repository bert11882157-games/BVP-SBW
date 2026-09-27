package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.diagnostics.EliteAudioPlayback;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

/** Exact instance/file tracing; never attributes a remote sound to the listener's selected gun. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Inject(method = "tick(Z)V", at = @At("RETURN"))
    private void superbWarfare$observeActiveSounds(boolean paused, CallbackInfo ci) {
        EliteAudioPlayback.observeExisting(instanceToChannel.keySet());
        if (!paused) com.atsuishio.superbwarfare.client.sound.spatial.SpatialDoppler.apply(instanceToChannel);
    }

    @Inject(method = "play", at = @At("RETURN"))
    private void superbWarfare$resolvedSound(SoundInstance instance, CallbackInfo ci) {
        if (instance != null) EliteAudioPlayback.resolved(instance);
    }

    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void superbWarfare$stoppedSound(SoundInstance instance, CallbackInfo ci) {
        EliteAudioPlayback.stopped(instance, "sound_manager_stop");
    }
}
