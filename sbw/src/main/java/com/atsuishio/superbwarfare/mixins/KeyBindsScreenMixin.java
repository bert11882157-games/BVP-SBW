package com.atsuishio.superbwarfare.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows the managed vehicle-binding notice once for each Key Binds screen instance. */
@Mixin(KeyBindsScreen.class)
public abstract class KeyBindsScreenMixin {
    @Unique
    private boolean sbw$vehicleBindingNoticeShown;

    @Inject(method = "init", at = @At("TAIL"))
    private void sbw$showVehicleBindingNotice(CallbackInfo ci) {
        if (sbw$vehicleBindingNoticeShown) return;
        sbw$vehicleBindingNoticeShown = true;
        SystemToast.add(
                Minecraft.getInstance().getToasts(),
                SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.literal("Bert's vehicle pack keybinds will not conflict with others."),
                null
        );
    }
}
