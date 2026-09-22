package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.input.VehicleControlBindings;
import com.atsuishio.superbwarfare.client.input.PlaneJoystickSensitivityEntry;
import com.atsuishio.superbwarfare.client.input.PlanePitchInversionEntry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.controls.KeyBindsList;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/** Keeps legacy SBW bindings functional and persistent without exposing their retired category. */
@Mixin(KeyBindsList.class)
public class KeyBindsListMixin {
    @Unique
    private KeyMapping[] sbw$visibleMappings;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;"
            )
    )
    private KeyMapping[] superbwarfare$visibleControlMappings(Options options) {
        sbw$visibleMappings = Arrays.stream(options.keyMappings)
                .filter(VehicleControlBindings::isVisibleInControls)
                .toArray(KeyMapping[]::new);
        return sbw$visibleMappings;
    }

    /** A real category row, not a fake unbound action or a new input profile. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void superbwarfare$appendWatercraftHeading(
            KeyBindsScreen screen, Minecraft minecraft, CallbackInfo ci
    ) {
        if (sbw$visibleMappings == null) return;
        KeyMapping[] sorted = sbw$visibleMappings;
        sbw$visibleMappings = null;
        Arrays.sort(sorted);
        int row = 0;
        int insertion = -1;
        int planeSettingInsertion = -1;
        String previousCategory = null;
        for (KeyMapping mapping : sorted) {
            String category = mapping.getCategory();
            if (VehicleControlBindings.WATERCRAFT_CATEGORY.equals(category)) return;
            if (!category.equals(previousCategory)) {
                row++;
                if (VehicleControlBindings.PLANE_CATEGORY.equals(category)) planeSettingInsertion = row;
            }
            previousCategory = category;
            row++;
            if (VehicleControlBindings.DRONE_CATEGORY.equals(category)) insertion = row;
        }
        KeyBindsList list = (KeyBindsList) (Object) this;
        if (insertion >= 0 && insertion <= list.children().size()) {
            list.children().add(insertion, list.new CategoryEntry(
                    Component.translatable(VehicleControlBindings.WATERCRAFT_CATEGORY)));
        }
        if (planeSettingInsertion >= 0 && planeSettingInsertion <= list.children().size()) {
            list.children().add(planeSettingInsertion, new PlaneJoystickSensitivityEntry(list));
            list.children().add(planeSettingInsertion + 1, new PlanePitchInversionEntry(list));
            list.children().add(planeSettingInsertion + 2, new PlaneJoystickSensitivityEntry(list, true));
        }
    }
}
