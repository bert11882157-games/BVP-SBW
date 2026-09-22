package com.yourname.berts_vehicle_pack.mixin;

import com.yourname.berts_vehicle_pack.client.renderer.BvpKomodoVehicleVisual;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects the custom-mesh adapter inside Komodo's existing vehicle visualizer registration. */
@Pseudo
@Mixin(targets = "com.norwood.komodo.client.render.kmodo.KmodoFlywheelRegistrar", remap = false)
public abstract class BvpKomodoRegistrarMixin {
    @Inject(method = "lambda$ensureRegistered$0", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bvp$createMeshVisual(VisualizationContext context, Entity entity, float partialTick,
                                            CallbackInfoReturnable<Object> callback) {
        if (entity instanceof ArmoredVehicleEntity vehicle) {
            callback.setReturnValue(new BvpKomodoVehicleVisual(context, vehicle, partialTick));
        }
    }
}
