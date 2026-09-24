package com.atsuishio.superbwarfare.mixins.compat;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vehicle instruments already display speed in the configured game units. */
@Pseudo
@Mixin(targets = "com.example.examplemod.SpeedometerHUD", remap = false)
public abstract class SoundBarrierHudMixin {
    @Inject(method = "onRenderGui", at = @At("HEAD"), cancellable = true)
    private static void sbw$oneSpeedDisplay(RenderGuiEvent.Post event, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.getVehicle() instanceof VehicleEntity) ci.cancel();
    }
}
