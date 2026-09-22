package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.client.input.VehicleWeaponSelectionInput;
import com.atsuishio.superbwarfare.network.NetworkRegistry;
import com.atsuishio.superbwarfare.network.message.send.ChangeVehicleSeatMessage;
import com.atsuishio.superbwarfare.network.message.send.SwitchVehicleWeaponMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    /**
     * 在可切换座位的载具上，按下潜行键+数字键时切换座位
     * 在有武器的载具上，按下数字键时切换武器
     */
    @Inject(method = "handleKeybinds()V", at = @At("HEAD"), cancellable = true)
    private void handleKeybinds(CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        Options options = minecraft.options;

        if (player == null || !(player.getVehicle() instanceof VehicleEntity vehicle)) return;

        // A FRONT state carried across a mount transition is not a valid helicopter camera state.
        if (vehicle.getVehicleType() == VehicleType.HELICOPTER) {
            if (options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
                options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            // Consume the mapped click before this mixin's existing hotbar cancellation can
            // terminate handleKeybinds; this keeps mouse/keyboard rebinds deterministic.
            if (options.keyTogglePerspective.consumeClick()) {
                options.setCameraType(options.getCameraType() == CameraType.FIRST_PERSON
                        ? CameraType.THIRD_PERSON_BACK
                        : CameraType.FIRST_PERSON);
            }
        }

        var index = -1;
        for (int i = 0; i < 9; ++i) {
            if (options.keyHotbarSlots[i].isDown()) {
                index = i;
                break;
            }
        }
        if (index == -1) return;

        // shift+数字键 座位更改
        if (vehicle.getMaxPassengers() > 1
                && options.keyShift.isDown()
                && index < vehicle.getMaxPassengers()
                && vehicle.getNthEntity(index) == null
        ) {
            ci.cancel();
            options.keyHotbarSlots[index].consumeClick();

            NetworkRegistry.PACKET_HANDLER.sendToServer(new ChangeVehicleSeatMessage(index));
            vehicle.changeSeat(player, index);

            return;
        }

        var seatIndex = vehicle.getSeatIndex(player);

        if (vehicle.banHand(player)) {
            ci.cancel();
            options.keyHotbarSlots[index].consumeClick();

            // 数字键 武器切换
            if (!options.keyShift.isDown()
                    && !VehicleWeaponSelectionInput.defersPrimary(options.keyHotbarSlots[index])
                    && vehicle.hasWeapon(seatIndex)
                    && vehicle.getWeaponIndex(seatIndex) != index) {
                if (ClientEventHandler.switchVehicleWeaponCooldown <= 0) {
                    NetworkRegistry.PACKET_HANDLER.sendToServer(new SwitchVehicleWeaponMessage(seatIndex, index, false));
                    ClientEventHandler.switchVehicleWeaponCooldown = 3;
                }
            }
        }
    }

}
