package com.atsuishio.superbwarfare.mixins.compat;

import com.atsuishio.superbwarfare.compat.SoundBarrierCompat;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tamara.sonic.util.SonicSoundManager", remap = false)
public abstract class SoundBarrierSoundMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void sbw$noSyntheticListener(ServerPlayer player, CallbackInfo ci) {
        if (SoundBarrierCompat.dispatching()) ci.cancel();
    }
}
