package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleOperatorPose;
import com.atsuishio.superbwarfare.item.curio.ParachuteItem;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Shadow
    @Final
    public ModelPart leftArm;

    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow @Final public ModelPart leftLeg;

    @Shadow @Final public ModelPart rightLeg;

    @Shadow @Final public ModelPart body;

    @Shadow @Final public ModelPart head;

    @Shadow @Final public ModelPart hat;

    @Unique
    private final VehicleOperatorPose.State superbwarfare$operatorPose = new VehicleOperatorPose.State();

    @Unique
    private boolean superbwarfare$lowMountedPose;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void restoreOperatorPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                     float ageInTicks, float headYaw, float headPitch, CallbackInfo ci) {
        superbwarfare$operatorPose.restore(body, head, hat, leftArm, rightArm);
        // Humanoid models are reused for other players: undo only our previous offset.
        if (superbwarfare$lowMountedPose) {
            body.y -= 10; head.y -= 10; hat.y -= 10;
            leftArm.y -= 10; rightArm.y -= 10;
            leftLeg.y -= 10; rightLeg.y -= 10;
            superbwarfare$lowMountedPose = false;
        }
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At(value = "TAIL")
    )
    private void setupAnim(LivingEntity livingEntity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (ParachuteItem.isParachuteOpen(livingEntity)) {
            this.leftArm.xRot = -180 * Mth.DEG_TO_RAD;
            this.rightArm.xRot = -180 * Mth.DEG_TO_RAD;

            this.leftArm.yRot = -15 * Mth.DEG_TO_RAD;
            this.rightArm.yRot = 15 * Mth.DEG_TO_RAD;

            this.leftLeg.xRot = 0;
            this.rightLeg.xRot = 0;
            this.leftLeg.yRot = 0;
            this.rightLeg.yRot = 0;

            this.body.xRot = 0;
            this.body.yRot = 0;
            this.body.zRot = 0;
        }

        if (livingEntity.getVehicle() instanceof VehicleEntity vehicle) {
            var index = vehicle.getSeatIndex(livingEntity);
            var seats = vehicle.computed().seats();
            if (index >= seats.size() || index < 0) return;
            var seat = seats.get(index);

            if (seat.pose.equals("Pilot")) {
                this.head.xRot = 0;
                this.head.yRot = 0;
                this.head.zRot = 0;
                this.hat.xRot = 0;
                this.hat.yRot = 0;
                this.hat.zRot = 0;

                this.rightArm.xRot = -55 * Mth.DEG_TO_RAD;
                this.rightArm.yRot = -15f * Mth.DEG_TO_RAD;
                this.rightArm.zRot = -30f * Mth.DEG_TO_RAD;
            }

            // Tow
            if (seat.pose.equals("Tow")) {
                this.head.xRot = 0;
                this.hat.xRot = 0;

                this.leftArm.yRot = 45 * Mth.DEG_TO_RAD;
                this.leftArm.xRot = -115 * Mth.DEG_TO_RAD;

                this.rightArm.yRot = 25 * Mth.DEG_TO_RAD;
                this.rightArm.xRot = -115 * Mth.DEG_TO_RAD;
            }

            // BF6的坦克挂票
            if (seat.pose.equals("Climb")) {
                this.leftArm.xRot = -112.5f * Mth.DEG_TO_RAD;
                this.rightArm.xRot = -112.5f * Mth.DEG_TO_RAD;
            }

            // 站姿
            if (seat.pose.equals("MountedLowSeated")) {
                // Ground-mounted low guns have no chair. Keep the entity root at ground
                // level and lower the seated mesh until its legs/bottom meet that ground.
                body.y += 10; head.y += 10; hat.y += 10;
                leftArm.y += 10; rightArm.y += 10;
                leftLeg.y += 10; rightLeg.y += 10;
                superbwarfare$lowMountedPose = true;
                leftLeg.xRot = rightLeg.xRot = -Mth.HALF_PI;
                leftLeg.yRot = 0.12F; rightLeg.yRot = -0.12F;
                leftLeg.zRot = rightLeg.zRot = 0;
                leftArm.xRot = rightArm.xRot = -60 * Mth.DEG_TO_RAD;
                leftArm.yRot = rightArm.yRot = leftArm.zRot = rightArm.zRot = 0;
            }

            if (seat.pose.equals("Stand")) {
                this.leftLeg.xRot = 0 * Mth.DEG_TO_RAD;
                this.leftLeg.yRot = 0 * Mth.DEG_TO_RAD;
                this.leftLeg.zRot = 0 * Mth.DEG_TO_RAD;

                this.rightLeg.xRot = 0 * Mth.DEG_TO_RAD;
                this.rightLeg.yRot = 0 * Mth.DEG_TO_RAD;
                this.rightLeg.zRot = 0 * Mth.DEG_TO_RAD;
            }

            // 机枪
            if (seat.pose.equals("MachineGunStand")) {
                var x = -90;

                this.leftArm.xRot = x * Mth.DEG_TO_RAD;
                this.leftArm.yRot = 0 * Mth.DEG_TO_RAD;
                this.leftArm.zRot = 0 * Mth.DEG_TO_RAD;

                this.rightArm.xRot = x * Mth.DEG_TO_RAD;
                this.rightArm.yRot = 0 * Mth.DEG_TO_RAD;
                this.rightArm.zRot = 0 * Mth.DEG_TO_RAD;

                this.leftLeg.xRot = 0 * Mth.DEG_TO_RAD;
                this.leftLeg.yRot = 0 * Mth.DEG_TO_RAD;
                this.leftLeg.zRot = 0 * Mth.DEG_TO_RAD;

                this.rightLeg.xRot = 0 * Mth.DEG_TO_RAD;
                this.rightLeg.yRot = 0 * Mth.DEG_TO_RAD;
                this.rightLeg.zRot = 0 * Mth.DEG_TO_RAD;

                VehicleOperatorPose.apply(livingEntity, vehicle, seat,
                        ageInTicks - livingEntity.tickCount, body, head, hat, leftArm, rightArm,
                        superbwarfare$operatorPose);
            }
        }

        // 趴下持枪
        if (livingEntity.getMainHandItem().getItem() instanceof GunItem && livingEntity.getPose() == Pose.SWIMMING && !livingEntity.isSwimming()) {
            this.hat.xRot = (livingEntity.getViewXRot(1) - 90) * Mth.DEG_TO_RAD;
            this.head.xRot = (livingEntity.getViewXRot(1) - 90) * Mth.DEG_TO_RAD;
            this.hat.yRot = 0;
            this.head.yRot = 0;

            this.leftArm.xRot = (-180 + livingEntity.getViewXRot(1)) * Mth.DEG_TO_RAD;
            this.rightArm.xRot = (-180 + livingEntity.getViewXRot(1))* Mth.DEG_TO_RAD;

            this.leftArm.yRot = 0 * Mth.DEG_TO_RAD;
            this.rightArm.yRot = 0 * Mth.DEG_TO_RAD;

            this.leftArm.zRot = -30 * Mth.DEG_TO_RAD;
            this.rightArm.zRot = 0 * Mth.DEG_TO_RAD;

            this.rightArm.x = -3f;
            this.leftArm.x = 3f;
        }
    }
}
