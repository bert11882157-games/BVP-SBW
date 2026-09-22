package com.atsuishio.superbwarfare.client.renderer.vehicle;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Presentation-only grip fitting. Seat/body placement and aim remain externally owned. */
public final class VehicleOperatorPose {
    private static final double MODEL_UNITS_PER_BLOCK = 16 / 0.9375;
    private static final double MODEL_ORIGIN_Y = 24.016;

    private VehicleOperatorPose() {
    }

    /** Reusable state owned by one humanoid model, restored before its next setupAnim. */
    public static final class State {
        private final VehicleOperatorPoseMath.Result result = new VehicleOperatorPoseMath.Result();
        private final float[] original = new float[30];
        private boolean applied;

        public void restore(ModelPart body, ModelPart head, ModelPart hat,
                            ModelPart leftArm, ModelPart rightArm) {
            if (!applied) return;
            restore(body, 0);
            restore(head, 6);
            restore(hat, 12);
            restore(leftArm, 18);
            restore(rightArm, 24);
            applied = false;
        }

        private void capture(ModelPart part, int offset) {
            original[offset] = part.x;
            original[offset + 1] = part.y;
            original[offset + 2] = part.z;
            original[offset + 3] = part.xRot;
            original[offset + 4] = part.yRot;
            original[offset + 5] = part.zRot;
        }

        private void restore(ModelPart part, int offset) {
            part.setPos(original[offset], original[offset + 1], original[offset + 2]);
            part.xRot = original[offset + 3];
            part.yRot = original[offset + 4];
            part.zRot = original[offset + 5];
        }
    }

    public static void apply(LivingEntity entity, VehicleEntity vehicle, SeatInfo seat,
                             float partialTicks, ModelPart body, ModelPart head, ModelPart hat,
                             ModelPart leftArm, ModelPart rightArm, State state) {
        var policy = seat.getOperatorPose();
        if (policy == null || !"MachineGunStand".equals(seat.pose)
                || !(entity instanceof AbstractClientPlayer player)
                || vehicle.isRemoved() || vehicle.isWreck()
                || policy.getLeftHandAttachment().isBlank()
                || policy.getRightHandAttachment().isBlank()) return;
        int seatIndex = vehicle.getSeatIndex(entity);
        if (seatIndex < 0 || !vehicle.isPassengerWeaponStationWeapon(seatIndex,
                vehicle.getSelectedWeapon(seatIndex))) return;
        float partial = Float.isFinite(partialTicks) ? Mth.clamp(partialTicks, 0, 1) : 0;
        int epoch = vehicle.getAimPresentationEpoch();
        var frame = vehicle.resolveAimPresentationFrame(entity, partial);
        if (frame == null || frame.getPresentationEpoch() != epoch) return;
        Vec3 left = frame.getAttachments().point(policy.getLeftHandAttachment(), Vec3.ZERO);
        Vec3 right = frame.getAttachments().point(policy.getRightHandAttachment(), Vec3.ZERO);
        if (left == null || right == null) return;
        double x = Mth.lerp(partial, entity.xo, entity.getX());
        double y = Mth.lerp(partial, entity.yo, entity.getY());
        double z = Mth.lerp(partial, entity.zo, entity.getZ());
        double yaw = Math.toRadians(Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot));
        double cosine = Math.cos(yaw);
        double sine = Math.sin(yaw);
        double lx = left.x - x, ly = left.y - y, lz = left.z - z;
        double rx = right.x - x, ry = right.y - y, rz = right.z - z;
        var result = state.result;
        double palmOffset = "slim".equals(player.getModelName()) ? 0.5 : 1.0;
        if (!VehicleOperatorPoseMath.solve(
                (cosine * lx + sine * lz) * MODEL_UNITS_PER_BLOCK,
                MODEL_ORIGIN_Y - ly * MODEL_UNITS_PER_BLOCK,
                (sine * lx - cosine * lz) * MODEL_UNITS_PER_BLOCK,
                (cosine * rx + sine * rz) * MODEL_UNITS_PER_BLOCK,
                MODEL_ORIGIN_Y - ry * MODEL_UNITS_PER_BLOCK,
                (sine * rx - cosine * rz) * MODEL_UNITS_PER_BLOCK,
                palmOffset, policy.getMaxForwardLeanDegrees(), policy.getMaxBackwardLeanDegrees(),
                result)) return;

        state.capture(body, 0);
        state.capture(head, 6);
        state.capture(hat, 12);
        state.capture(leftArm, 18);
        state.capture(rightArm, 24);
        state.applied = true;
        body.setPos(0, result.bodyY, result.bodyZ);
        body.xRot = result.leanRadians;
        body.yRot = 0;
        body.zRot = 0;
        head.setPos(0, result.bodyY, result.bodyZ);
        hat.setPos(0, result.bodyY, result.bodyZ);
        leftArm.setPos(5, result.shoulderY, result.shoulderZ);
        rightArm.setPos(-5, result.shoulderY, result.shoulderZ);
        leftArm.xRot = result.leftXRot;
        leftArm.yRot = result.leftYRot;
        leftArm.zRot = result.leftZRot;
        rightArm.xRot = result.rightXRot;
        rightArm.yRot = result.rightYRot;
        rightArm.zRot = result.rightZRot;
    }
}
