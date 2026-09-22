package com.atsuishio.superbwarfare.client.camera;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Banking about an already-positioned authored cockpit eye, using one render-pose sample. */
public final class FixedWingCockpitCamera {
    private FixedWingCockpitCamera() {}

    public record Frame(Entity passenger, float bodyYaw, float bodyPitch, float bodyRoll) {}

    public static Frame capture(Entity passenger, boolean firstPerson, float partialTick) {
        if (!firstPerson || !Float.isFinite(partialTick) || !(passenger instanceof Player)
                || !(passenger.getVehicle() instanceof VehicleEntity vehicle)
                || !vehicle.isFixedWingFlightVehicle()
                || AircraftArmamentClient.isPodActive(vehicle)
                || vehicle.isPassengerStationLocalAimController(passenger)) return null;
        int seatIndex = vehicle.getSeatIndex(passenger);
        var seats = vehicle.computed().seats();
        if (seatIndex < 0 || seatIndex >= seats.size()) return null;
        var camera = seats.get(seatIndex).getCameraPos();
        if (camera == null || camera.getEyeAttachment() == null || camera.getEyeAttachment().isBlank()) return null;
        var pose = vehicle.resolveVehicleSeatPose(passenger, partialTick, false);
        if (pose == null || !Double.isFinite(pose.getEyePosition().x)
                || !Double.isFinite(pose.getEyePosition().y) || !Double.isFinite(pose.getEyePosition().z)) return null;
        // Match the authored Vehicle transform's applied render interval, not the separate
        // instrument receipt clock. Capture all axes before Camera.setup for this same partial.
        float yaw = vehicle.getResolvedChassisYaw(partialTick);
        float pitch = vehicle.getPitch(partialTick);
        float roll = vehicle.getRoll(partialTick);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)) return null;
        return new Frame(passenger, yaw, pitch, roll);
    }

    public static float rollDegrees(float viewYaw, float viewPitch, float bodyYaw, float bodyPitch, float bodyRoll) {
        if (!Float.isFinite(viewYaw) || !Float.isFinite(viewPitch) || !Float.isFinite(bodyYaw) || !Float.isFinite(bodyPitch)
                || !Float.isFinite(bodyRoll)) return 0.0F;
        double y = Math.toRadians(bodyYaw), p = Math.toRadians(bodyPitch), r = Math.toRadians(bodyRoll);
        double cy = Math.cos(y), sy = Math.sin(y), cp = Math.cos(p), sp = Math.sin(p);
        double cr = Math.cos(r), sr = Math.sin(r);
        // Native body up: Ry(-yaw) Rx(pitch) Rz(roll) * +Y. Euler-equivalent poses stay equivalent.
        double upX = -cy * sr - sy * sp * cr, upY = cp * cr, upZ = -sy * sr + cy * sp * cr;
        double vy = Math.toRadians(viewYaw), vp = Math.toRadians(viewPitch);
        double cvy = Math.cos(vy), svy = Math.sin(vy), cvp = Math.cos(vp), svp = Math.sin(vp);
        double horizontal = -upX * cvy - upZ * svy;
        double vertical = -upX * svy * svp + upY * cvp + upZ * cvy * svp;
        if (horizontal * horizontal + vertical * vertical < 1.0E-12) {
            // Looking directly along body up: use its perpendicular body-right axis, not an Euler heading.
            double rightX = -cy * cr + sy * sp * sr, rightY = -cp * sr, rightZ = -sy * cr - cy * sp * sr;
            horizontal = rightX * svy * svp - rightY * cvp - rightZ * cvy * svp;
            vertical = -rightX * cvy - rightZ * svy;
        }
        return (float) Math.toDegrees(Math.atan2(horizontal, vertical));
    }
}
