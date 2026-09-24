package com.atsuishio.superbwarfare.mixins.compat;

import com.atsuishio.superbwarfare.compat.SoundBarrierCompat;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Interoperability boundary for the separately installed Forge SoundBarrier 1.4.0 binary. */
@Pseudo
@Mixin(targets = "com.example.examplemod.SonicEvents", remap = false)
public abstract class SoundBarrierEventsMixin {
    @Inject(method = "onPlayerTick", at = @At("HEAD"), cancellable = true)
    private static void sbw$authoritativeCrossing(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        if (SoundBarrierCompat.suppressAutomatic(event)) ci.cancel();
    }

    @ModifyVariable(method = "onPlayerTick", at = @At("STORE"), ordinal = 0)
    private static Entity sbw$effectSource(Entity original) { return SoundBarrierCompat.effectVehicle(original); }

    @ModifyVariable(method = "onPlayerTick", at = @At("STORE"), ordinal = 0)
    private static double sbw$alreadyAcceptedSpeed(double original) { return SoundBarrierCompat.effectSpeed(original); }

    @ModifyConstant(method = "onPlayerTick", constant = @Constant(doubleValue = 70))
    private static double sbw$threshold(double original) { return SoundBarrierCompat.dispatching() ? 350.0 / 3.6 : original; }

    @ModifyConstant(method = "onPlayerTick", constant = @Constant(intValue = 18))
    private static int sbw$groundDustOwner(int original) { return SoundBarrierCompat.dispatching() ? 0 : original; }

    // Stop after the addon's particle effect, before its unrelated glass-destruction scan and duplicate sound calls.
    @Inject(method = "onPlayerTick", at = @At(value = "CONSTANT", args = "intValue=25"), cancellable = true)
    private static void sbw$effectOnly(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        if (SoundBarrierCompat.dispatching()) ci.cancel();
    }

    // The optional addon ships in SRG names; this exact invocation is pinned by the compatibility fixture.
    @Redirect(method = "onPlayerTick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerLevel;m_8767_(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private static <T extends ParticleOptions> int sbw$batchParticles(ServerLevel level, T type,
            double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        if (SoundBarrierCompat.collect(type, x, y, z, count, dx, dy, dz, speed)) return 0;
        return level.sendParticles(type, x, y, z, count, dx, dy, dz, speed);
    }
}
