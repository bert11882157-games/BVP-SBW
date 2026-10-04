package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.screens.VehicleSoundsScreen;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.SoundOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/**
 * Options > Music & Sounds (owner 2026-10-01): the five vehicle-mix sliders join the two-column list of Minecraft's
 * category sliders. Vanilla's list has an odd number of category sliders (Voice/Speech sits alone in the last
 * row), so appending them to that same {@code addSmall} call fills that gap and adds two more full rows above
 * "Device", instead of hiding them behind the "Vehicle Sounds..." button (which stays, with its reset button).
 * {@code require = 0}: purely cosmetic, so a mod that rebuilds this screen must not crash the game; the button
 * still reaches the sliders then.
 */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin {
    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V",
            ordinal = 0), require = 0)
    private OptionInstance<?>[] sbw$appendVehicleSoundSliders(OptionInstance<?>[] categorySliders) {
        OptionInstance<?>[] vehicle = VehicleSoundsScreen.mainListOptions();
        OptionInstance<?>[] out = Arrays.copyOf(categorySliders, categorySliders.length + vehicle.length);
        System.arraycopy(vehicle, 0, out, categorySliders.length, vehicle.length);
        return out;
    }
}
