package com.yourname.berts_vehicle_pack.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.renderer.GeoRenderer;

/** Custom vehicles supply their real meshes through the Komodo visual adapter. */
@Pseudo
@Mixin(targets = "com.norwood.komodo.client.render.kmodo.KmodoFlywheelModelCache", remap = false)
public abstract class BvpKomodoNativeModelMixin {
    @Inject(method = "getModels", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bvp$useCustomMeshSource(GeoRenderer<?> renderer, GeoVehicleEntity vehicle,
                                              CallbackInfoReturnable<Object> callback) {
        // Native proxy cubes are only an error fallback, never an instancing source.
        if (vehicle instanceof ArmoredVehicleEntity) callback.setReturnValue(null);
    }
}
