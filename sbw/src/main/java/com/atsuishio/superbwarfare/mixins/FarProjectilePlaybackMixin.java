package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.FarProjectilePlayback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancel the whole client tick, including projectile subclass motion and trail emission. */
@Mixin(ClientLevel.class)
public abstract class FarProjectilePlaybackMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void sbw$holdServerPausedProjectile(Entity entity, CallbackInfo callback) {
        if (FarProjectilePlayback.hold(entity)) callback.cancel();
    }
}
