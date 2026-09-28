package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.config.client.DisplayConfig;
import com.atsuishio.superbwarfare.client.camera.FixedWingCockpitCamera;
import com.atsuishio.superbwarfare.client.camera.VehicleCameraFrameDiagnostic;
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient;
import com.atsuishio.superbwarfare.client.input.AircraftCollisionPicking;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.init.ModMobEffects;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void sbw$beginCameraFrameDiagnostic(float partialTick, long limitTime, PoseStack view, CallbackInfo ci) {
        com.atsuishio.superbwarfare.client.camera.VehiclePassengerCamera.enforce();
        VehicleCameraFrameDiagnostic.beginFrame(partialTick);
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"), index = 0)
    private PoseStack sbw$observeProjectionBeforeEffects(PoseStack projection) {
        return VehicleCameraFrameDiagnostic.beforeProjectionEffects(projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            shift = At.Shift.AFTER))
    private void sbw$observeProjectionAfterEffects(float partialTick, long limitTime, PoseStack view, CallbackInfo ci) {
        VehicleCameraFrameDiagnostic.afterProjectionEffects();
    }

    @Inject(method = "pick(F)V", at = @At("HEAD"))
    private void sbw$beginPhysicalUiPick(float partialTicks, CallbackInfo ci) {
        AircraftCollisionPicking.beginUiPick(partialTicks);
    }

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void sbw$endPhysicalUiPick(float partialTicks, CallbackInfo ci) {
        AircraftCollisionPicking.endUiPick();
    }

    @Inject(method = "bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", at = @At("HEAD"), cancellable = true)
    public void bobView(PoseStack p_109139_, float p_109140_, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player != null) {
            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof GunItem && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON) {
                ci.cancel();
            }
        }
    }

    // From Immersive_Aircraft
    @Shadow
    @Final
    private Camera mainCamera;

    @Unique
    private FixedWingCockpitCamera.Frame sbw$authoredFixedWingCamera;

    @SuppressWarnings("ConstantValue")
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"))
    public void superbWarfare$renderWorld(float tickDelta, long limitTime, PoseStack matrices, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        sbw$authoredFixedWingCamera = FixedWingCockpitCamera.capture(minecraft.getCameraEntity(),
                minecraft.options.getCameraType() == CameraType.FIRST_PERSON, tickDelta);
        Entity entity = mainCamera.getEntity();

        matrices.mulPose(Axis.ZP.rotationDegrees(ClientEventHandler.cameraRoll));

        if (entity instanceof Player player && !player.isSpectator() && player.hasEffect(ModMobEffects.SHOCK.get())) {
            float shakeStrength = (float) DisplayConfig.SHOCK_SCREEN_SHAKE.get() / 100.0f;
            if (shakeStrength <= 0.0f) return;
            matrices.mulPose(Axis.ZP.rotationDegrees((float) Mth.nextDouble(RandomSource.create(), 8, 12) * shakeStrength));
        }

    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            shift = At.Shift.AFTER))
    private void sbw$applyCurrentVehicleBank(float tickDelta, long limitTime,
                                             PoseStack matrices, CallbackInfo ci) {
        VehicleCameraFrameDiagnostic.afterCameraSetup(mainCamera, matrices);
        try {
            sbw$applyVehicleBank(tickDelta, matrices);
        } finally {
            VehicleCameraFrameDiagnostic.afterVehicleBank(matrices);
        }
    }

    @Unique
    private void sbw$applyVehicleBank(float tickDelta, PoseStack matrices) {
        var frame = sbw$authoredFixedWingCamera;
        sbw$authoredFixedWingCamera = null;
        if (frame != null && mainCamera.getEntity() == frame.passenger() && !mainCamera.isDetached()) {
            // Camera.setup already placed the authored eye. Rotate around it; never translate it again.
            matrices.mulPose(Axis.ZP.rotationDegrees(FixedWingCockpitCamera.rollDegrees(
                    mainCamera.getYRot(), mainCamera.getXRot(), frame.bodyYaw(), frame.bodyPitch(), frame.bodyRoll())));
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = mainCamera.getEntity();
        // Legacy cockpit banking must also use this frame's completed camera. Before setup it
        // read the previous held view on release, mixing old freelook angles with current body axes.
        if (frame == null && entity != null
                && minecraft.options.getCameraType().isFirstPerson()
                && entity.getRootVehicle() instanceof VehicleEntity vehicle && !mainCamera.isDetached()
                && !AircraftArmamentClient.sensorView(vehicle)
                && !vehicle.isPassengerStationLocalAimController(entity)) {
            // rotate camera
            float a = Mth.wrapDegrees(mainCamera.getYRot() - Mth.lerp(tickDelta, vehicle.yRotO, vehicle.getYRot()));

            var seats = vehicle.computed().seats();
            int index = vehicle.getSeatIndex(entity);
            if (index < 0 || index >= seats.size()) return;

            var seat = seats.get(index);

            if (seat.transform.equals("VehicleFlat")) {
                a = 0;
            }

            float r = (Mth.abs(a) - 90f) / 90f;
            float r2;
            if (Mth.abs(a) <= 90f) {
                r2 = a / 90f;
            } else {
                if (a < 0) {
                    r2 = -(180f + a) / 90f;
                } else {
                    r2 = (180f - a) / 90f;
                }
            }

            matrices.mulPose(Axis.ZP.rotationDegrees(-r * vehicle.getRoll(tickDelta) - r2 * vehicle.getViewXRot(tickDelta)));

            // The attachment resolver already places an authored eye in the rotated vehicle
            // frame. Applying the legacy player-eye offset again moves the effective view
            // into cockpit geometry (especially on nose-low helicopters).
            var camera = seat.getCameraPos();
            boolean authoredEye = camera != null && camera.getEyeAttachment() != null
                    && !camera.getEyeAttachment().isBlank();
            if (!vehicle.useFixedCameraPos(entity) && !authoredEye) {
                // fetch eye offset
                float eye = entity.getEyeHeight();

                // transform eye offset to match aircraft rotation
                Vector3f offset = new Vector3f(0, -eye, 0);
                Quaternionf quaternion = Axis.XP.rotationDegrees(0.0f);
                quaternion.mul(Axis.YP.rotationDegrees(-vehicle.getViewYRot(tickDelta)));
                quaternion.mul(Axis.XP.rotationDegrees(vehicle.getViewXRot(tickDelta)));
                quaternion.mul(Axis.ZP.rotationDegrees(vehicle.getRoll(tickDelta)));
                offset.rotate(quaternion);

                // apply camera offset
                matrices.mulPose(Axis.XP.rotationDegrees(mainCamera.getXRot()));
                matrices.mulPose(Axis.YP.rotationDegrees(mainCamera.getYRot() + 180.0f));
                matrices.translate(offset.x(), offset.y() + eye, offset.z());
                matrices.mulPose(Axis.YP.rotationDegrees(-mainCamera.getYRot() - 180.0f));
                matrices.mulPose(Axis.XP.rotationDegrees(-mainCamera.getXRot()));
            }
        }
    }

    @Inject(method = "getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F",
            at = @At("RETURN"), cancellable = true)
    private static void getNightVisionScale(LivingEntity pLivingEntity, float pNanoTime, CallbackInfoReturnable<Float> cir) {
        boolean hasThermalImagingVehicle = false;

        if (pLivingEntity.getVehicle() instanceof VehicleEntity vehicle) {
            var index = vehicle.getSeatIndex(pLivingEntity);
            var seats = vehicle.computed().seats();
            if (index < 0 || index >= seats.size()) return;

            var seat = seats.get(index);
            if (seat.hasThermalImaging) {
                hasThermalImagingVehicle = true;
            }
        }

        if (ClientEventHandler.activeThermalImaging || ClientEventHandler.hasThermalImagingGoggles() || hasThermalImagingVehicle) {
            cir.cancel();
            cir.setReturnValue(pLivingEntity.hasEffect(MobEffects.NIGHT_VISION) ? 1f : 0f);
        }
    }
}
