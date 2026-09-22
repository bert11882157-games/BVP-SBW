package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.input.VehicleControlBindings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Arrays;

/** Keeps legacy SBW bindings functional and persistent without exposing their retired category. */
@Mixin(KeyBindsList.class)
public class KeyBindsListMixin {

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"
            )
    )
    private KeyMapping[] superbwarfare$visibleControlMappings(Options options) {
        return Arrays.stream(options.keyMappings)
                .filter(VehicleControlBindings::isVisibleInControls)
                .toArray(KeyMapping[]::new);
    }
}
