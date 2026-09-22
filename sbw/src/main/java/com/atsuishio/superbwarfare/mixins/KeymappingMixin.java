package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.input.VehicleControlBindings;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.settings.IKeyConflictContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public class KeymappingMixin {

    @Shadow
    private InputConstants.Key key;

    @Shadow
    private int clickCount;

    @Inject(method = "isDown()Z", at = @At("HEAD"), cancellable = true)
    private void superbwarfare$suppressOrdinaryVehicleOverlap(CallbackInfoReturnable<Boolean> cir) {
        if (VehicleControlBindings.shouldSuppress((KeyMapping) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "consumeClick()Z", at = @At("HEAD"), cancellable = true)
    public void consumeClick(CallbackInfoReturnable<Boolean> cir) {
        if (VehicleControlBindings.shouldSuppress((KeyMapping) (Object) this)) {
            this.clickCount = 0;
            cir.setReturnValue(false);
            return;
        }

        Player player = Minecraft.getInstance().player;
        if (player == null || !(player.getVehicle() instanceof VehicleEntity vehicle)) return;

        for (int i = 0; i < 9; i++) {
            if (Minecraft.getInstance().options.keyHotbarSlots[i].getKey() == key) {
                if (vehicle.getMaxPassengers() > 1
                        && Screen.hasShiftDown()
                        && i < vehicle.getMaxPassengers()
                        && vehicle.getNthEntity(i) == null
                ) {
                    if (this.clickCount > 0) {
                        --this.clickCount;
                    }
                    cir.setReturnValue(false);
                }

                if (vehicle.banHand(player)) {
                    if (this.clickCount > 0) {
                        --this.clickCount;
                    }
                    cir.setReturnValue(false);
                }
            }
        }
    }

    @Inject(method = "same(Lnet/minecraft/client/KeyMapping;)Z", at = @At("HEAD"), cancellable = true)
    private void superbwarfare$hideManagedControlConflict(
            KeyMapping other,
            CallbackInfoReturnable<Boolean> cir
    ) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (VehicleControlBindings.shouldSuppressConflict(self, other)) {
            cir.setReturnValue(false);
        } else if (VehicleControlBindings.hasSameGroupKeyConflict(self, other)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "compareTo(Lnet/minecraft/client/KeyMapping;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void superbwarfare$orderManagedControlCategories(
            KeyMapping other,
            CallbackInfoReturnable<Integer> cir
    ) {
        KeyMapping self = (KeyMapping) (Object) this;
        String selfCategory = self.getCategory();
        String otherCategory = other.getCategory();
        if (selfCategory.equals(otherCategory)) return;

        boolean selfManaged = isManagedCategory(selfCategory);
        boolean otherManaged = isManagedCategory(otherCategory);
        if (selfManaged || otherManaged) {
            cir.setReturnValue(Integer.compare(categoryRank(selfCategory), categoryRank(otherCategory)));
        }
    }

    /**
     * Forge implements this as an interface default, so the controls screen bypasses the same()
     * check for modifier conflicts. A concrete target method applies the same per-category
     * warning policy without changing runtime input arbitration or unrelated mod conflicts.
     */
    public boolean hasKeyModifierConflict(KeyMapping other) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (VehicleControlBindings.shouldSuppressConflict(self, other)) {
            return false;
        }

        IKeyConflictContext selfContext = self.getKeyConflictContext();
        IKeyConflictContext otherContext = other.getKeyConflictContext();
        if (VehicleControlBindings.isSameManagedCategory(self, other)
                || selfContext.conflicts(otherContext) || otherContext.conflicts(selfContext)) {
            return self.getKeyModifier().matches(other.getKey())
                    || other.getKeyModifier().matches(self.getKey());
        }
        return false;
    }

    private static boolean isManagedCategory(String category) {
        return VehicleControlBindings.BVP_CATEGORY.equals(category)
                || VehicleControlBindings.LAND_CATEGORY.equals(category)
                || VehicleControlBindings.PLANE_CATEGORY.equals(category)
                || VehicleControlBindings.HELICOPTER_CATEGORY.equals(category)
                || VehicleControlBindings.DRONE_CATEGORY.equals(category)
                || VehicleControlBindings.WATERCRAFT_CATEGORY.equals(category)
                || VehicleControlBindings.TACZ_CATEGORY.equals(category);
    }

    private static int categoryRank(String category) {
        return switch (category) {
            case KeyMapping.CATEGORY_MOVEMENT -> 1;
            case KeyMapping.CATEGORY_GAMEPLAY -> 2;
            case KeyMapping.CATEGORY_INVENTORY -> 3;
            case VehicleControlBindings.BVP_CATEGORY -> 4;
            case VehicleControlBindings.LAND_CATEGORY -> 5;
            case VehicleControlBindings.PLANE_CATEGORY -> 6;
            case VehicleControlBindings.HELICOPTER_CATEGORY -> 7;
            case VehicleControlBindings.DRONE_CATEGORY -> 8;
            case VehicleControlBindings.WATERCRAFT_CATEGORY -> 9;
            case VehicleControlBindings.TACZ_CATEGORY -> 10;
            case KeyMapping.CATEGORY_CREATIVE -> 11;
            case KeyMapping.CATEGORY_MULTIPLAYER -> 12;
            case KeyMapping.CATEGORY_INTERFACE -> 13;
            case KeyMapping.CATEGORY_MISC -> 14;
            default -> 15;
        };
    }
}
