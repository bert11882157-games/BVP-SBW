package com.atsuishio.superbwarfare.mixins.compat;

import com.atsuishio.superbwarfare.client.camera.VehicleCameraEffectOwnership;
import com.atsuishio.superbwarfare.client.camera.VehicleCameraFrameDiagnostic;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional CameraOverhaul boundary, including its Senkos optimized update implementation. */
@Pseudo
@Mixin(targets = "mirsario.cameraoverhaul.CameraSystem", priority = 900, remap = false)
public abstract class CameraOverhaulCameraSystemMixin {
    @Shadow @Final private Vector3d prevCameraEulerRot;
    @Shadow @Final private Vector3d prevEntityVelocity;
    @Shadow private double prevVerticalVelocityPitchOffset;
    @Shadow private double prevForwardVelocityPitchOffset;
    @Shadow private double turningRollTargetOffset;
    @Shadow private double prevStrafingRollOffset;
    @Shadow private double cameraSwayFactor;
    @Shadow private double cameraSwayFactorTarget;
    @Shadow private boolean msInit;

    @Unique private final VehicleCameraEffectOwnership.State sbw$cameraOwnership = new VehicleCameraEffectOwnership.State();

    @Inject(method = "modifyCameraTransform", at = @At("HEAD"), cancellable = true)
    private void sbw$singleVehicleCameraOwner(CallbackInfo ci) {
        boolean owned = VehicleCameraEffectOwnership.ownsMountedCamera();
        boolean suppressed = sbw$cameraOwnership.suppressTransform(owned);
        VehicleCameraFrameDiagnostic.foreignCameraTransform(owned, suppressed);
        if (suppressed) ci.cancel();
    }

    // Mixin 0.8.5 prepends the later/lower-priority HEAD injection. The transformed-bytecode
    // fixture proves this executes before the optimizer's priority-1000 cancellable replacement.
    @Inject(method = "onCameraUpdate", at = @At("HEAD"), cancellable = true)
    private void sbw$rebasePedestrianEffects(CallbackInfo ci) {
        boolean owned = VehicleCameraEffectOwnership.ownsMountedCamera();
        boolean suppressed = sbw$cameraOwnership.suppressUpdate(owned);
        VehicleCameraFrameDiagnostic.foreignCameraUpdate(owned, suppressed);
        if (suppressed) {
            ci.cancel();
            return;
        }
        if (!sbw$cameraOwnership.resetBeforeOrdinaryUpdate()) return;
        prevVerticalVelocityPitchOffset = 0.0;
        prevForwardVelocityPitchOffset = 0.0;
        turningRollTargetOffset = 0.0;
        prevStrafingRollOffset = 0.0;
        cameraSwayFactor = 0.0;
        cameraSwayFactorTarget = 0.0;
        msInit = false;
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        prevCameraEulerRot.set(camera.getXRot(), camera.getYRot(), 0.0);
        var entity = mc.getCameraEntity();
        if (entity == null) prevEntityVelocity.zero();
        else {
            var velocity = entity.getDeltaMovement();
            prevEntityVelocity.set(velocity.x, velocity.y, velocity.z);
        }
        // Both supported update implementations clear offsetTransform before recomputing it.
        // No old offset is applied during the projection pass preceding this fresh update.
    }
}
