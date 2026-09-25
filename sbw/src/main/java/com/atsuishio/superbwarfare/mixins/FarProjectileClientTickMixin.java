package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess;
import com.atsuishio.superbwarfare.api.projectile.FarProjectileTracking;
import com.atsuishio.superbwarfare.config.client.FarVehicleRenderConfig;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the server tracker admits these entities; detached far terrain does not tick client chunks. */
@Mixin(Entity.class)
public abstract class FarProjectileClientTickMixin {
    @Inject(method = "isAlwaysTicking", at = @At("HEAD"), cancellable = true)
    private void sbw$tickTrackedProjectile(CallbackInfoReturnable<Boolean> callback) {
        Entity entity = (Entity) (Object) this;
        // Deterministic ballistic rounds only integrate their own motion on the client (no terrain
        // loads), so they keep flying outside client-ticking sections instead of freezing mid-air.
        if (entity.level().isClientSide && entity instanceof FarProjectileAccess
                && (FarVehicleRenderConfig.ENABLED.get() || FarProjectileTracking.holdsFreeFlight(entity)))
            callback.setReturnValue(true);
    }
}
