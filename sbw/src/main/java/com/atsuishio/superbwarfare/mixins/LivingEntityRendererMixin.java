package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.client.renderer.FixedWingRiderPose;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> extends EntityRenderer<T> implements RenderLayerParent<T, M> {
    @Shadow
    protected M model;

    protected LivingEntityRendererMixin(EntityRendererProvider.Context pContext) {
        super(pContext);
    }

    @Inject(method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V", at = @At("HEAD"), cancellable = true)
    protected void setupRotations(T entity, PoseStack matrices, float pAgeInTicks, float pRotationYaw, float tickDelta, CallbackInfo ci) {
        if (entity.getRootVehicle() != entity && entity.getRootVehicle() instanceof VehicleEntity vehicle) {
            var seats = vehicle.computed().seats();
            int index = vehicle.getSeatIndex(entity);
            if (index < 0 || index >= seats.size()) return;

            ci.cancel();
            var seat = seats.get(index);

            String anchor = seat.getBodyAttachment();
            if (anchor == null || anchor.isBlank()) anchor = seat.transform;
            var frame = vehicle.getVehicleAttachmentSnapshot(tickDelta).transform(anchor);
            var body = frame == null ? vehicle.getTransformFromString(seat.transform, tickDelta) : frame.matrix();
            var resolvedSeat = vehicle.resolveVehicleSeatPose(entity, tickDelta, false);
            var seatPosition = seat.getPosition();
            var target = resolvedSeat == null
                    ? body.transformPosition(new Vector3d(seatPosition.x, seatPosition.y, seatPosition.z))
                    : new Vector3d(resolvedSeat.getBodyPosition().x, resolvedSeat.getBodyPosition().y,
                            resolvedSeat.getBodyPosition().z);
            var offset = target.sub(new Vector3d(Mth.lerp(tickDelta, entity.xOld, entity.getX()),
                    Mth.lerp(tickDelta, entity.yOld, entity.getY()),
                    Mth.lerp(tickDelta, entity.zOld, entity.getZ())));
            // Every rider follows the rendered attachment instead of a separately interpolated entity origin.
            if (offset.isFinite()) matrices.translate(offset.x, offset.y, offset.z);

            if (!seat.getCanRotateBody()) {
                matrices.mulPose(FixedWingRiderPose.rotation(body, seat.getOrientation()));
                if (EliteDiagnostics.isClientEnabled()) {
                    EliteDiagnostics.recordClient(vehicle.level().getGameTime(), "rider", "seat_render",
                            "vehicle", vehicle.getUUID(), "rider", entity.getUUID(), "seat", index,
                            "partial_tick", tickDelta, "anchor", anchor, "correction", offset.toString(),
                            "pitch", vehicle.getPitch(tickDelta), "roll", vehicle.getRoll(tickDelta),
                            "speed_mps", vehicle.getDeltaMovement().length() * 20.0,
                            "render_matrix", matrices.last().pose().toString());
                }
            } else {
                float transformYaw = (float) VehicleVecUtils.getYRotFromVector(vehicle.getTransformDirectionNoOrientation(tickDelta, entity));
                var passengerWeaponStationYawRot = Axis.YP.rotationDegrees(-transformYaw);

                Quaterniond quaterniond = vehicle.getRotationFromString(seat.transform, tickDelta).mul(new Quaterniond(passengerWeaponStationYawRot));
                Quaternionf quaternionf = new Quaternionf(quaterniond.x, quaterniond.y, quaterniond.z, quaterniond.w);

                matrices.mulPose(quaternionf);
                matrices.mulPose(Axis.YP.rotationDegrees(180.0F - pRotationYaw));
            }

            float scale = vehicle.getPassengerRenderScale();

            if (Minecraft.getInstance().player != null && ClientEventHandler.zoomVehicle && entity.getRootVehicle() == Minecraft.getInstance().player.getRootVehicle()) {
                scale = 0;
            }

            matrices.scale(scale, scale, scale);
        }
    }

    @Inject(method = "isBodyVisible(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    protected void isBodyVisible(T pLivingEntity, CallbackInfoReturnable<Boolean> cir) {
        if (ClientEventHandler.activeThermalImaging) {
            cir.cancel();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
    private void observeRenderedPilot(T entity, float yaw, float partialTick, PoseStack pose,
                                      MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!EliteDiagnostics.isClientEnabled() || !(model instanceof HumanoidModel<?> humanoid)
                || !(entity.getVehicle() instanceof VehicleEntity vehicle)) return;
        int index = vehicle.getSeatIndex(entity);
        if (index < 0 || index >= vehicle.computed().seats().size()) return;
        var seat = vehicle.computed().seats().get(index);
        String anchor = seat.getBodyAttachment();
        if (anchor == null || anchor.isBlank()) anchor = seat.transform;
        var frame = vehicle.getVehicleAttachmentSnapshot(partialTick).transform(anchor);
        var body = frame == null ? vehicle.getTransformFromString(seat.transform, partialTick) : frame.matrix();
        var target = body.transformPosition(new Vector3d(seat.getPosition().x,
                seat.getPosition().y, seat.getPosition().z));
        pose.pushPose();
        try {
            humanoid.body.translateAndRotate(pose);
            Vector3f pelvis = pose.last().pose().transformPosition(new Vector3f(0, 12F / 16F, 0));
            EliteDiagnostics.recordClient(vehicle.level().getGameTime(), "rider", "mesh_draw",
                    "vehicle", vehicle.getUUID(), "rider", entity.getUUID(), "seat", index,
                    "partial_tick", partialTick, "riding", model.riding,
                    "seat_world", target.toString(), "pelvis_render", pelvis.toString(),
                    "body_matrix", java.util.Arrays.toString(pose.last().pose().get(new float[16])),
                    "aircraft_matrix", java.util.Arrays.toString(body.get(new double[16])));
        } finally {
            pose.popPose();
        }
    }
}
